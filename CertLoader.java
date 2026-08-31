package com.adcb.certificateloader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CertLoader {

    private static final Logger logger = Logger.getLogger(CertLoader.class.getName());
    
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private static final String SECRET_NAME = System.getenv().getOrDefault(
            "ADCB_Jks_Certificate", "ADCB_Jks_Certificate"
    );
    
    private static final ZoneId EXPIRY_ZONE = ZoneId.of(
            System.getenv().getOrDefault("EXPIRY_TIMEZONE", "Asia/Dubai")
    );

    private static final SecretsManagerClient secretsClient = SecretsManagerClient.builder()
            .region(Region.of(System.getenv().getOrDefault("AWS_REGION_NAME", "eu-central-1")))
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofSeconds(10))
                    .apiCallAttemptTimeout(Duration.ofSeconds(5))
                    .build())
            .build();

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Counters purely for observability - see how many times each path was hit
    private static final AtomicInteger cacheHitCount = new AtomicInteger(0);
    private static final AtomicInteger freshFetchCount = new AtomicInteger(0);

    private static final class CertCache {
        final KeyStore keyStore;
        final char[] password;
        final LocalDate expiryDate; // parsed from the secret's own "expiryDate" field
 
        CertCache(KeyStore keyStore, char[] password, LocalDate expiryDate) {
            this.keyStore = keyStore;
            this.password = password;
            this.expiryDate = expiryDate;
        }
    }

    private static volatile CertCache cache;

    static {
        try {
        	logger.info("[COLD START] Pre-warming CertLoader during Lambda INIT phase...");
            cache = fetchAndBuildCache();
            logger.info("[COLD START] Pre-warm SUCCESS - cert and password cached before first invocation, "
                    + "expiryDate=" + cache.expiryDate);
            int fetchNumber = freshFetchCount.incrementAndGet();        
            logger.info("[FRESH FETCH #" + fetchNumber + "] Fetched secret from AWS Secrets Manager: " + SECRET_NAME);
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "[COLD START] Pre-warm FAILED - will retry on first invocation", e);
        }
    }

    private CertLoader() {
    }

    public static KeyStore getKeyStore() {
        return getOrInitializeCache().keyStore;
    }

    public static char[] getPassword() {
        return getOrInitializeCache().password.clone();
    }
    
    
    
  
    private static boolean isPastKnownExpiry(CertCache cache) {

        /*
         * If cache or expiry date is missing,
         * the certificate must be loaded again.
         */
        if (cache == null || cache.expiryDate == null) {
            return true;
        }

        LocalDate today = LocalDate.now(EXPIRY_ZONE);

        /*
         * Return true when:
         *
         * today >= expiryDate
         *
         * The certificate will be loaded again on
         * or after the configured expiry date.
         */
        return !today.isBefore(cache.expiryDate);
    }


    private static CertCache getOrInitializeCache() {

        /*
         * Read the current certificate cache.
         */
        CertCache result = cache;


        /*
         * FAST PATH
         *
         * Certificate is available in cache and
         * has not reached the configured expiry date.
         */
        if (result != null && !isPastKnownExpiry(result)) {

            cacheHitCount.incrementAndGet();

            logger.fine(
                    "Using the existing certificate from the cache."
            );

            return result;
        }


        /*
         * Cache is empty or the certificate has reached
         * the configured expiry date.
         *
         * Only one thread in the same Lambda container
         * can load a new certificate at a time.
         */
        synchronized (CertLoader.class) {

            /*
             * Read the cache again after acquiring the lock.
             *
             * Another request may have already loaded
             * a valid certificate while this request
             * was waiting for the lock.
             */
            result = cache;


            if (result != null && !isPastKnownExpiry(result)) {

                cacheHitCount.incrementAndGet();

                logger.info(
                        "A valid certificate is already available in the cache. "
                        + "Using the cached certificate."
                );

                return result;
            }


            logger.info(
                    "Certificate cache is empty or has reached its configured "
                    + "expiry date. Loading the latest certificate from "
                    + "AWS Secrets Manager."
            );


            try {

                /*
                 * Load and validate the complete certificate,
                 * password and expiry date before replacing
                 * the existing cache.
                 */
                CertCache newCache = fetchAndBuildCache();


                /*
                 * Replace the existing cache only after
                 * the new certificate is loaded successfully.
                 */
                cache = newCache;

                int fetchNumber =
                        freshFetchCount.incrementAndGet();


                logger.info(
                        "Latest certificate loaded successfully and the cache "
                        + "has been updated. Expiry date: "
                        + newCache.expiryDate
                        + ". Secret fetch count: "
                        + fetchNumber
                );


                return newCache;


            } catch (Exception e) {

                logger.log(
                        Level.SEVERE,
                        "Unable to load the latest certificate from "
                        + "AWS Secrets Manager.",
                        e
                );


                /*
                 * If loading the latest certificate fails,
                 * use the existing certificate only when it
                 * is still valid.
                 */
                LocalDate today =
                        LocalDate.now(EXPIRY_ZONE);


                if (result != null
                        && result.expiryDate != null
                        && today.isBefore(result.expiryDate)) {

                    logger.warning(
                            "The latest certificate could not be loaded, "
                            + "but the existing certificate is still valid. "
                            + "Continuing to use the existing certificate."
                    );

                    return result;
                }


                /*
                 * There is no certificate in cache, or the
                 * existing certificate is no longer valid.
                 *
                 * Do not continue with an invalid certificate.
                 */
                logger.severe(
                        "No valid certificate is available. "
                        + "The request cannot continue."
                );


                throw new RuntimeException(
                        "Certificate loading failed and no valid cached "
                        + "certificate is available.",
                        e
                );
            }
        }
    }
   

    private static CertCache fetchAndBuildCache() {
        logger.info("Fetching secret from AWS Secrets Manager: " + SECRET_NAME);

        String secretJson;
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(SECRET_NAME)
                    .build();

            secretJson = secretsClient.getSecretValue(request).secretString();
        } catch (SecretsManagerException e) {
            logger.log(Level.SEVERE, "AWS Secrets Manager error while loading secret: "
                    + e.awsErrorDetails().errorMessage(), e);
            throw new RuntimeException("Failed to fetch certificate secret from AWS", e);
        }

        if (secretJson == null || secretJson.isEmpty()) {
            logger.log(Level.SEVERE, "Secret string returned from AWS Secrets Manager is empty for: " + SECRET_NAME);
            throw new IllegalStateException("Secret string returned from AWS Secrets Manager is empty.");
        }

        JsonNode certNode;
        JsonNode passNode;
        JsonNode expiryNode;
        try {
        	
            JsonNode secret = objectMapper.readTree(secretJson);
            String cert_key  =  System.getenv().getOrDefault("cert_key", "cert_base64");
            
            logger.info("Base64 secert key name"+" [CertLoaderClass][fetchAndBuildCache]"+" "+cert_key);
            
            String password_key =  System.getenv().getOrDefault("password_key", "jks_password");
            logger.info("JKS Password  secert key name"+" [CertLoaderClass][fetchAndBuildCache]"+" "+password_key);
            
            String expiry_key = System.getenv().getOrDefault("expiry_key", "expiryDate");
            logger.info("expiryDate: "+" [CertLoaderClass][fetchAndBuildCache]"+" "+expiry_key);
            
            logger.info("Using secret field names - cert_key=" + cert_key
                    + ", password_key=" + password_key
                    + ", expiry_key=" + expiry_key);
            
            certNode = secret.get(cert_key);
            passNode = secret.get(password_key);
            expiryNode = secret.get(expiry_key);  
            
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to parse secret JSON for: " + SECRET_NAME, e);
            throw new RuntimeException("Failed to parse certificate secret JSON", e);
        }

        if (certNode == null || passNode == null) {
            logger.log(Level.SEVERE, "Secret JSON missing required fields 'cert_base64' or 'jks_password' for: " + SECRET_NAME);
            throw new IllegalArgumentException(
                    "Secret JSON missing required fields: 'cert_base64' or 'jks_password'");
        }
        
    
        byte[] certBytes;
        char[] password;
        LocalDate expiryDate;
        try {
            certBytes = Base64.getDecoder().decode(certNode.asText());
            password = passNode.asText().toCharArray();
         // Expects ISO-8601 date format, e.g. "2026-08-31"
            expiryDate = LocalDate.parse(expiryNode.asText());
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to decode base64 certificate data for: " + SECRET_NAME, e);
            throw new RuntimeException("Failed to decode certificate data", e);
        }

        String keystoreType = System.getenv().getOrDefault("KEYSTORE_TYPE", "JKS");
        KeyStore ks;
        ByteArrayInputStream bais = null;
        
        try {
            ks = KeyStore.getInstance(keystoreType);
            bais = new ByteArrayInputStream(certBytes);
            ks.load(bais, password);
            logger.info("CertLoader successfully initialized KeyStore with " + ks.size() + " alias(es)");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load and parse certificate KeyStore for: " + SECRET_NAME, e);
            throw new RuntimeException("Failed to initialize CertLoader", e);
        } finally {
            if (bais != null) {
                try {
                    bais.close();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to close ByteArrayInputStream", e);
                }
            }
        }

        return new CertCache(ks, password, expiryDate);
    }
}



 
