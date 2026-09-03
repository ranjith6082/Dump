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
 
import java.io.ByteArrayInputStream;

import java.security.KeyStore;

import java.time.Duration;

import java.util.Arrays;

import java.util.Base64;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.logging.Level;

import java.util.logging.Logger;
 
public final class CertLoader {
 
    private static final Logger logger =

            Logger.getLogger(CertLoader.class.getName());
 
    // =========================================================

    // CONFIGURATION

    // =========================================================
 
    /*

     * Secrets Manager secret:

     *

     * {

     *   "cert_base64": "...",

     *   "jks_password": "..."

     * }

     */

    private static final String SECRET_NAME =

            System.getenv().getOrDefault(

                    "ADCB_Jks_Certificate",

                    "ADCB_Jks_Certificate"

            );
 
    /*

     * SSM parameter containing the certificate version.

     *

     * Example:

     *

     * /adcb/certloader/cert-version = V1

     *

     * During rotation:

     *

     * /adcb/certloader/cert-version = V2

     */

    private static final String SSM_VERSION_PARAM =

            System.getenv().getOrDefault(

                    "CERT_VERSION_SSM_PARAM",

                    "/adcb/certloader/cert-version"

            );
 
    /*

     * SSM is checked only once every 10 minutes

     * per Lambda execution environment.

     */

    private static final long VERSION_CHECK_TTL_MILLIS =

            Long.parseLong(

                    System.getenv().getOrDefault(

                            "CERT_VERSION_CHECK_TTL_MS",

                            "600000"

                    )

            );
 
    private static final Region REGION =

            Region.of(

                    System.getenv().getOrDefault(

                            "AWS_REGION_NAME",

                            "eu-central-1"

                    )

            );
 
    private static final String CERT_KEY =

            System.getenv().getOrDefault(

                    "cert_key",

                    "cert_base64"

            );
 
    private static final String PASSWORD_KEY =

            System.getenv().getOrDefault(

                    "password_key",

                    "jks_password"

            );
 
    private static final String KEYSTORE_TYPE =

            System.getenv().getOrDefault(

                    "KEYSTORE_TYPE",

                    "JKS"

            );
 
    // =========================================================

    // AWS CLIENTS

    // =========================================================
 
    private static final SecretsManagerClient secretsClient =

            SecretsManagerClient.builder()

                    .region(REGION)

                    .overrideConfiguration(

                            ClientOverrideConfiguration.builder()

                                    .apiCallTimeout(

                                            Duration.ofSeconds(10)

                                    )

                                    .apiCallAttemptTimeout(

                                            Duration.ofSeconds(5)

                                    )

                                    .build()

                    )

                    .build();
 
    private static final SsmClient ssmClient =

            SsmClient.builder()

                    .region(REGION)

                    .overrideConfiguration(

                            ClientOverrideConfiguration.builder()

                                    .apiCallTimeout(

                                            Duration.ofSeconds(5)

                                    )

                                    .apiCallAttemptTimeout(

                                            Duration.ofSeconds(3)

                                    )

                                    .build()

                    )

                    .build();
 
    private static final ObjectMapper objectMapper =

            new ObjectMapper();
 
    // =========================================================

    // OBSERVABILITY

    // =========================================================
 
    /*

     * Number of requests which reused the existing cache.

     */

    private static final AtomicInteger cacheHitCount =

            new AtomicInteger(0);
 
    /*

     * Number of actual Secrets Manager certificate fetches.

     */

    private static final AtomicInteger freshFetchCount =

            new AtomicInteger(0);
 
    /*

     * Number of actual SSM version-check attempts.

     */

    private static final AtomicInteger versionCheckCount =

            new AtomicInteger(0);
 
    // =========================================================

    // CACHE

    // =========================================================
 
    private static final class CertCache {
 
        final KeyStore keyStore;
 
        final char[] password;
 
        /*

         * SSM version associated with the certificate

         * currently stored in this cache.

         *

         * Can temporarily be null if SSM was unavailable

         * when the certificate was loaded.

         */

        final String version;
 
        CertCache(

                KeyStore keyStore,

                char[] password,

                String version) {
 
            this.keyStore = keyStore;

            this.password = password;

            this.version = version;

        }

    }
 
    /*

     * One cache per Lambda execution environment.

     *

     * volatile ensures visibility across threads.

     */

    private static volatile CertCache cache;
 
    /*

     * Last time SSM version was checked.

     *

     * This is also per execution environment.

     */

    private static volatile long lastVersionCheckMillis = 0L;
 
    // =========================================================

    // COLD START

    // =========================================================
 
    static {
 
        try {
 
            logger.info(

                    "[COLD START] Starting certificate pre-warm"

            );
 
            /*

             * SSM is ONLY a version-detection mechanism.

             *

             * It must NOT prevent certificate loading.

             */

            String version =

                    getCurrentSsmVersion();
 
            /*

             * IMPORTANT:

             *

             * Even if SSM returns null, we still fetch

             * the certificate from Secrets Manager.

             */

            cache =

                    fetchAndBuildCache(version);
 
            /*

             * We attempted an SSM check during INIT,

             * so start the TTL from this point.

             */

            lastVersionCheckMillis =

                    System.currentTimeMillis();
 
            freshFetchCount.incrementAndGet();
 
            if (version != null) {
 
                logger.info(

                        "[COLD START] Certificate loaded successfully. "

                                + "version=" + version

                );
 
            } else {
 
                logger.warning(

                        "[COLD START] Certificate loaded successfully "

                                + "but SSM version was unavailable. "

                                + "Version will be checked after TTL."

                );

            }
 
        } catch (Exception e) {
 
            /*

             * Only certificate loading failure leaves cache null.

             *

             * First invocation will retry.

             */

            logger.log(

                    Level.SEVERE,

                    "[COLD START] Certificate pre-warm failed. "

                            + "First request will retry.",

                    e

            );

        }

    }
 
    private CertLoader() {

    }
 
    // =========================================================

    // PUBLIC API

    // =========================================================
 
    public static KeyStore getKeyStore() {
 
        return getOrInitializeCache().keyStore;

    }
 
    public static char[] getPassword() {
 
        /*

         * Return a copy.

         *

         * Caller cannot modify the cached password directly.

         */

        return getOrInitializeCache()

                .password

                .clone();

    }
 
    // =========================================================

    // CACHE LOGIC

    // =========================================================
 
    private static CertCache getOrInitializeCache() {
 
        CertCache result = cache;
 
        // =====================================================

        // CASE 1: CACHE EMPTY

        // =====================================================
 
        if (result == null) {
 
            synchronized (CertLoader.class) {
 
                /*

                 * Double-check after acquiring lock.

                 */

                result = cache;
 
                if (result == null) {
 
                    logger.info(

                            "[CACHE MISS] Certificate cache is empty. "

                                    + "Loading certificate."

                    );
 
                    /*

                     * Try SSM to obtain the version.

                     *

                     * SSM failure does NOT stop certificate loading.

                     */

                    String version =

                            getCurrentSsmVersion();
 
                    /*

                     * This is an actual SSM check.

                     */

                    versionCheckCount.incrementAndGet();
 
                    /*

                     * Secrets Manager is the actual source

                     * of the certificate.

                     */

                    CertCache newCache =

                            fetchAndBuildCache(version);
 
                    /*

                     * Publish only after successful certificate

                     * loading and validation.

                     */

                    cache = newCache;
 
                    lastVersionCheckMillis =

                            System.currentTimeMillis();
 
                    int fetchNumber =

                            freshFetchCount.incrementAndGet();
 
                    if (version != null) {
 
                        logger.info(

                                "[FRESH FETCH #" + fetchNumber + "] "

                                        + "Certificate loaded. "

                                        + "version=" + version

                        );
 
                    } else {
 
                        logger.warning(

                                "[FRESH FETCH #" + fetchNumber + "] "

                                        + "Certificate loaded from Secrets Manager, "

                                        + "but SSM version unavailable."

                        );

                    }
 
                    return newCache;

                }
 
                /*

                 * Another thread initialized the cache

                 * while this thread was waiting for the lock.

                 */

                return cache;

            }

        }
 
        // =====================================================

        // CASE 2: CACHE EXISTS + TTL NOT EXPIRED

        // =====================================================
 
        long now =

                System.currentTimeMillis();
 
        if (now - lastVersionCheckMillis
< VERSION_CHECK_TTL_MILLIS) {
 
            return recordCacheHit(

                    result,

                    "within TTL"

            );

        }
 
        // =====================================================

        // CASE 3: TTL EXPIRED

        // =====================================================
 
        synchronized (CertLoader.class) {
 
            /*

             * IMPORTANT:

             *

             * Re-read cache after obtaining lock.

             *

             * Another thread may already have refreshed it.

             */

            result = cache;
 
            now =

                    System.currentTimeMillis();
 
            /*

             * Another thread may already have performed

             * the version check.

             */

            if (now - lastVersionCheckMillis
< VERSION_CHECK_TTL_MILLIS) {
 
                return recordCacheHit(

                        result,

                        "another thread already checked SSM"

                );

            }
 
            /*

             * Set timestamp BEFORE calling SSM.

             *

             * If SSM is slow or unavailable, another request

             * won't immediately call SSM again.

             */

            lastVersionCheckMillis = now;
 
            /*

             * This represents one actual SSM check.

             */

            int checkNumber =

                    versionCheckCount.incrementAndGet();
 
            logger.info(

                    "[SSM VERSION CHECK #" + checkNumber + "] "

                            + "Checking certificate version"

            );
 
            String currentVersion =

                    getCurrentSsmVersion();
 
            // =================================================

            // SSM FAILURE

            // =================================================
 
            if (currentVersion == null) {
 
                logger.warning(

                        "[SSM FAILED] Could not determine current "

                                + "certificate version. "

                                + "Keeping existing certificate. "

                                + "cachedVersion=" + result.version

                );
 
                return recordCacheHit(

                        result,

                        "SSM version unavailable"

                );

            }
 
            // =================================================

            // SAME VERSION

            // =================================================
 
            if (currentVersion.equals(result.version)) {
 
                return recordCacheHit(

                        result,

                        "certificate version unchanged: "

                                + currentVersion

                );

            }
 
            // =================================================

            // CACHE VERSION UNKNOWN

            // =================================================
 
            /*

             * This happens when:

             *

             * Cold start

             *     ↓

             * SSM unavailable

             *     ↓

             * Certificate successfully loaded

             *     ↓

             * cache.version = null

             *

             * Later SSM becomes available.

             *

             * We don't need to download the certificate again

             * because it was already freshly loaded from

             * Secrets Manager.

             */

            if (result.version == null) {
 
                logger.info(

                        "[VERSION RECONCILE] SSM version is now "

                                + "available: " + currentVersion

                                + ". Applying version label to "

                                + "existing certificate without "

                                + "another Secrets Manager call."

                );
 
                CertCache relabeledCache =

                        new CertCache(

                                result.keyStore,

                                result.password,

                                currentVersion

                        );
 
                cache =

                        relabeledCache;
 
                return recordCacheHit(

                        relabeledCache,

                        "version label reconciled"

                );

            }
 
            // =================================================

            // CERTIFICATE ROTATION

            // =================================================
 
            logger.warning(

                    "[CERT ROTATION] Certificate version changed. "

                            + "old=" + result.version

                            + ", new=" + currentVersion

                            + ". Fetching new certificate."

            );
 
            try {
 
                /*

                 * Fetch and completely validate the new certificate

                 * BEFORE replacing the old cache.

                 */

                CertCache newCache =

                        fetchAndBuildCache(currentVersion);
 
                /*

                 * New certificate is valid.

                 * Now replace the old cache.

                 */

                cache =

                        newCache;
 
                /*

                 * Clear old password after successful replacement.

                 */

                Arrays.fill(

                        result.password,

                        '\0'

                );
 
                int fetchNumber =

                        freshFetchCount.incrementAndGet();
 
                logger.info(

                        "[REFRESH SUCCESS #" + fetchNumber + "] "

                                + "Certificate refreshed successfully. "

                                + "newVersion=" + currentVersion

                );
 
                return newCache;
 
            } catch (Exception e) {
 
                /*

                 * IMPORTANT:

                 *

                 * New certificate failed.

                 *

                 * Keep the old certificate.

                 */

                logger.log(

                        Level.SEVERE,

                        "[REFRESH FAILED] New certificate could not "

                                + "be loaded. Keeping old certificate. "

                                + "version=" + result.version,

                        e

                );
 
                return result;

            }

        }

    }
 
    // =========================================================

    // CACHE HIT

    // =========================================================
 
    private static CertCache recordCacheHit(

            CertCache result,

            String reason) {
 
        int hitNumber =

                cacheHitCount.incrementAndGet();
 
        logger.fine(

                "[CACHE HIT #" + hitNumber + "] "

                        + reason

                        + ". version="

                        + result.version

        );
 
        return result;

    }
 
    // =========================================================

    // SSM VERSION

    // =========================================================
 
    private static String getCurrentSsmVersion() {
 
        try {
 
            GetParameterRequest request =

                    GetParameterRequest.builder()

                            .name(SSM_VERSION_PARAM)

                            .build();
 
            String version =

                    ssmClient

                            .getParameter(request)

                            .parameter()

                            .value();
 
            if (version == null ||

                    version.isBlank()) {
 
                logger.warning(

                        "[SSM VERSION] Parameter value is empty."

                );
 
                return null;

            }
 
            return version.trim();
 
        } catch (ParameterNotFoundException e) {
 
            logger.warning(

                    "[SSM VERSION] Parameter not found: "

                            + SSM_VERSION_PARAM

            );
 
            return null;
 
        } catch (Exception e) {
 
            logger.log(

                    Level.WARNING,

                    "[SSM VERSION] Failed to read parameter. "

                            + "Existing certificate will be retained.",

                    e

            );
 
            return null;

        }

    }
 
    // =========================================================

    // SECRETS MANAGER

    // =========================================================
 
    private static CertCache fetchAndBuildCache(

            String version) {
 
        logger.info(

                "[SECRETS MANAGER] Fetching certificate. "

                        + "version=" + version

        );
 
        String secretJson;
 
        try {
 
            GetSecretValueRequest request =

                    GetSecretValueRequest.builder()

                            .secretId(SECRET_NAME)

                            .build();
 
            secretJson =

                    secretsClient

                            .getSecretValue(request)

                            .secretString();
 
        } catch (SecretsManagerException e) {
 
            String errorCode =

                    e.awsErrorDetails() != null

                            ? e.awsErrorDetails().errorCode()

                            : "UNKNOWN";
 
            logger.log(

                    Level.SEVERE,

                    "[SECRETS MANAGER] Failed to fetch certificate. "

                            + "errorCode=" + errorCode,

                    e

            );
 
            throw new RuntimeException(

                    "Failed to fetch certificate from Secrets Manager",

                    e

            );

        }
 
        // =====================================================

        // SECRET VALIDATION

        // =====================================================
 
        if (secretJson == null ||

                secretJson.isBlank()) {
 
            throw new IllegalStateException(

                    "Secrets Manager returned empty secret"

            );

        }
 
        JsonNode secret;

        JsonNode certNode;

        JsonNode passwordNode;
 
        try {
 
            secret =

                    objectMapper.readTree(secretJson);
 
            certNode =

                    secret.get(CERT_KEY);
 
            passwordNode =

                    secret.get(PASSWORD_KEY);
 
        } catch (Exception e) {
 
            throw new RuntimeException(

                    "Failed to parse certificate secret JSON",

                    e

            );

        }
 
        if (certNode == null ||

                passwordNode == null) {
 
            throw new IllegalArgumentException(

                    "Secret missing required certificate/password fields"

            );

        }
 
        // =====================================================

        // DECODE CERTIFICATE

        // =====================================================
 
        byte[] certBytes = null;

        char[] password = null;
 
        try {
 
            certBytes =

                    Base64.getDecoder()

                            .decode(certNode.asText());
 
            password =

                    passwordNode.asText()

                            .toCharArray();
 
            // ================================================

            // LOAD KEYSTORE

            // ================================================
 
            KeyStore keyStore =

                    KeyStore.getInstance(

                            KEYSTORE_TYPE

                    );
 
            try (

                    ByteArrayInputStream inputStream =

                            new ByteArrayInputStream(certBytes)

            ) {
 
                keyStore.load(

                        inputStream,

                        password

                );

            }
 
            if (keyStore.size() == 0) {
 
                throw new IllegalStateException(

                        "KeyStore contains no entries"

                );

            }
 
            logger.info(

                    "[CERT LOADED] Certificate validated successfully. "

                            + "version=" + version

                            + ", aliases=" + keyStore.size()

            );
 
            return new CertCache(

                    keyStore,

                    password,

                    version

            );
 
        } catch (Exception e) {
 
            /*

             * New password/certificate failed validation.

             * Don't leave the password in memory unnecessarily.

             */

            if (password != null) {

                Arrays.fill(

                        password,

                        '\0'

                );

            }
 
            throw new RuntimeException(

                    "Failed to load certificate",

                    e

            );
 
        } finally {
 
            /*

             * certBytes contains the raw decoded JKS.

             * Clear it after KeyStore.load().

             */

            if (certBytes != null) {

                Arrays.fill(

                        certBytes,

                        (byte) 0

                );

            }

        }

    }
 
    // =========================================================

    // METRICS

    // =========================================================
 
    public static int getCacheHitCount() {
 
        return cacheHitCount.get();

    }
 
    public static int getFreshFetchCount() {
 
        return freshFetchCount.get();

    }
 
    public static int getVersionCheckCount() {
 
        return versionCheckCount.get();

    }

}
 
