package com.adcb.cert;
 
import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;
 
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

import software.amazon.awssdk.regions.Region;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import software.amazon.awssdk.services.ssm.SsmClient;

import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import software.amazon.awssdk.services.ssm.model.SsmException;
 
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
 
    // SSM parameter that holds the "version" marker for the cert.

    // This should be updated (e.g. by your rotation process / pipeline)

    // whenever the underlying secret in Secrets Manager changes.

    private static final String SSM_VERSION_PARAM = System.getenv().getOrDefault(

            "CERT_VERSION_SSM_PARAM", "/adcb/certloader/cert-version"

    );
 
    private static final SecretsManagerClient secretsClient = SecretsManagerClient.builder()

            .region(Region.of(System.getenv().getOrDefault("AWS_REGION_NAME", "eu-central-1")))

            .overrideConfiguration(ClientOverrideConfiguration.builder()

                    .apiCallTimeout(Duration.ofSeconds(10))

                    .apiCallAttemptTimeout(Duration.ofSeconds(5))

                    .build())

            .build();
 
    private static final SsmClient ssmClient = SsmClient.builder()

            .region(Region.of(System.getenv().getOrDefault("AWS_REGION_NAME", "eu-central-1")))

            .overrideConfiguration(ClientOverrideConfiguration.builder()

                    .apiCallTimeout(Duration.ofSeconds(5))

                    .apiCallAttemptTimeout(Duration.ofSeconds(3))

                    .build())

            .build();
 
    private static final ObjectMapper objectMapper = new ObjectMapper();
 
    // Counters purely for observability - see how many times each path was hit

    private static final AtomicInteger cacheHitCount = new AtomicInteger(0);

    private static final AtomicInteger freshFetchCount = new AtomicInteger(0);

    private static final AtomicInteger versionCheckCount = new AtomicInteger(0);
 
    private static final class CertCache {

        final KeyStore keyStore;

        final char[] password;

        final String version; // version marker this cache entry corresponds to
 
        CertCache(KeyStore keyStore, char[] password, String version) {

            this.keyStore = keyStore;

            this.password = password;

            this.version = version;

        }

    }
 
    private static volatile CertCache cache;
 
    static {

        try {

            logger.info("[COLD START] Pre-warming CertLoader during Lambda INIT phase...");

            String initialVersion = getCurrentSsmVersionSafe();

            cache = fetchAndBuildCache(initialVersion);

            logger.info("[COLD START] Pre-warm SUCCESS - cert and password cached before first invocation (version=" + initialVersion + ")");

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

        // 1. Always check the current version marker in SSM first (cheap/free call)

        String currentVersion = getCurrentSsmVersion();

        versionCheckCount.incrementAndGet();
 
        CertCache result = cache;
 
        // 2. Cache is empty (first invocation on this container) -> fetch fresh

        if (result == null) {

            synchronized (CertLoader.class) {

                result = cache;

                if (result == null) {

                    logger.info("[CACHE MISS] No cached value found - fetching fresh from Secrets Manager (attempt #"

                            + (freshFetchCount.get() + 1) + "), version=" + currentVersion);

                    cache = result = fetchAndBuildCache(currentVersion);

                    freshFetchCount.incrementAndGet();

                    logger.info("[CACHE MISS] Fresh fetch complete - cache now populated for this container");

                    return result;

                }

            }

        }
 
        // 3. Cache exists, but version has changed since we cached it -> refetch

        if (currentVersion != null && !currentVersion.equals(result.version)) {

            synchronized (CertLoader.class) {

                result = cache;

                if (currentVersion != null && !currentVersion.equals(result.version)) {

                    logger.info("[CACHE STALE] SSM version changed (cached=" + result.version

                            + ", current=" + currentVersion + ") - refetching from Secrets Manager");

                    cache = result = fetchAndBuildCache(currentVersion);

                    freshFetchCount.incrementAndGet();

                    logger.info("[CACHE STALE] Refetch complete - cache updated to version " + currentVersion);

                    return result;

                }

            }

        }
 
        // 4. Version unchanged -> cache is still valid

        int hitNumber = cacheHitCount.incrementAndGet();

        logger.info("[CACHE HIT] Version unchanged (" + currentVersion

                + ") - using cached cert/password, no Secrets Manager call made (hit #" + hitNumber + ")");

        return result;

    }
 
    /**

     * Reads the current cert version marker from SSM Parameter Store.

     * Throws on failure - caller decides how to handle (used at cold-start pre-warm

     * where a safe fallback variant is preferred).

     */

    private static String getCurrentSsmVersion() {

        try {

            GetParameterRequest request = GetParameterRequest.builder()

                    .name(SSM_VERSION_PARAM)

                    .build();

            return ssmClient.getParameter(request).parameter().value();

        } catch (ParameterNotFoundException e) {

            logger.log(Level.WARNING, "SSM parameter not found: " + SSM_VERSION_PARAM

                    + " - treating as no version info available (will not force refetch based on version)", e);

            return null;

        } catch (SsmException e) {

            logger.log(Level.WARNING, "Failed to read SSM version parameter: " + SSM_VERSION_PARAM

                    + " - falling back to existing cache if present", e);

            return null;

        }

    }
 
    /**

     * Safe variant used during static INIT (cold start pre-warm), where we don't

     * want a transient SSM error to block the whole pre-warm block.

     */

    private static String getCurrentSsmVersionSafe() {

        try {

            return getCurrentSsmVersion();

        } catch (Exception e) {

            logger.log(Level.WARNING, "Unexpected error reading SSM version during cold start", e);

            return null;

        }

    }
 
    private static CertCache fetchAndBuildCache(String version) {

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

            certNode = secret.get("cert_base64");

            passNode = secret.get("jks_password");

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
 
        return new CertCache(ks, password, version);

    }

}
 
