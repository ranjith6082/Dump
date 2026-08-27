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
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CertLoader {

    private static final Logger logger = Logger.getLogger(CertLoader.class.getName());

    private static final String SECRET_NAME = System.getenv().getOrDefault(
            "ADCB_Jks_Certificate", "ADCB_Jks_Certificate"
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

        CertCache(KeyStore keyStore, char[] password) {
            this.keyStore = keyStore;
            this.password = password;
        }
    }

    private static volatile CertCache cache;

    static {
        try {
            logger.info("[COLD START] Pre-warming CertLoader during Lambda INIT phase...");
            cache = fetchAndBuildCache();
            int fetchNumber = freshFetchCount.incrementAndGet();        
            logger.info("[COLD START] [FRESH FETCH #" + fetchNumber + "] Pre-warm SUCCESS - cert and password cached before first invocation");
   
            
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

    private static CertCache getOrInitializeCache() {
        CertCache result = cache;
        if (result == null) {
            synchronized (CertLoader.class) {
                result = cache;
                if (result == null) {
                    logger.info("[CACHE MISS] No cached value found - fetching fresh from Secrets Manager (attempt #"
                            + (freshFetchCount.get() + 1) + ")");
                    cache = result = fetchAndBuildCache();
                    freshFetchCount.incrementAndGet();
                    logger.info("[CACHE MISS] Fresh fetch complete - cache now populated for this container");
                    return result;
                }
            }
        }
        int hitNumber = cacheHitCount.getAndIncrement();
        logger.info("[CACHE HIT] Using cached cert/password - no Secrets Manager call made (hit #" + hitNumber + ")");
        return result;
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
        try {
        	
            JsonNode secret = objectMapper.readTree(secretJson);
            String cert_key  =  System.getenv().getOrDefault("cert_key", "cert_base64");
            
            logger.info("Base64 secert key name"+" [CertLoaderClass][fetchAndBuildCache]"+" "+cert_key);
            
           String password_key =  System.getenv().getOrDefault("password_key", "jks_password");
           logger.info("JKS Password  secert key name"+" [CertLoaderClass][fetchAndBuildCache]"+" "+password_key);
            
            certNode = secret.get(cert_key);
            passNode = secret.get(password_key);
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
        try {
            certBytes = Base64.getDecoder().decode(certNode.asText());
            password = passNode.asText().toCharArray();
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

        return new CertCache(ks, password);
    }
}



 
