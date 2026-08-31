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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CertLoader {

    private static final Logger LOGGER =
            Logger.getLogger(CertLoader.class.getName());

    private static final String SECRET_NAME =
            System.getenv().getOrDefault(
                    "ADCB_Jks_Certificate",
                    "ADCB_Jks_Certificate"
            );

    private static final ZoneId EXPIRY_ZONE =
            ZoneId.of(
                    System.getenv().getOrDefault(
                            "EXPIRY_TIMEZONE",
                            "Asia/Dubai"
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

    private static final String EXPIRY_KEY =
            System.getenv().getOrDefault(
                    "expiry_key",
                    "expiryDate"
            );

    private static final String KEYSTORE_TYPE =
            System.getenv().getOrDefault(
                    "KEYSTORE_TYPE",
                    "JKS"
            );

    private static final SecretsManagerClient SECRETS_CLIENT =
            SecretsManagerClient.builder()
                    .region(
                            Region.of(
                                    System.getenv().getOrDefault(
                                            "AWS_REGION_NAME",
                                            "eu-central-1"
                                    )
                            )
                    )
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

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    private static final AtomicInteger CACHE_HIT_COUNT =
            new AtomicInteger(0);

    private static final AtomicInteger FRESH_FETCH_COUNT =
            new AtomicInteger(0);

    private static volatile CertCache cache;

    private CertLoader() {
        // Utility class
    }

    private static final class CertCache {

        private final KeyStore keyStore;
        private final char[] password;
        private final LocalDate expiryDate;

        private CertCache(
                KeyStore keyStore,
                char[] password,
                LocalDate expiryDate) {

            this.keyStore = keyStore;
            this.password = password;
            this.expiryDate = expiryDate;
        }
    }

    static {

        try {

            LOGGER.info(
                    "[COLD START] Pre-warming CertLoader during Lambda INIT phase..."
            );

            cache = fetchAndBuildCache();

            int fetchNumber =
                    FRESH_FETCH_COUNT.incrementAndGet();

            LOGGER.info(
                    "[COLD START] Pre-warm SUCCESS. "
                            + "Certificate and password cached before first invocation. "
                            + "Expiry date="
                            + cache.expiryDate
                            + ". Secret fetch count="
                            + fetchNumber
            );

        } catch (Exception e) {

            LOGGER.log(
                    Level.WARNING,
                    "[COLD START] Pre-warm FAILED. "
                            + "Will retry on first invocation.",
                    e
            );
        }
    }

    public static KeyStore getKeyStore() {
        return getOrInitializeCache().keyStore;
    }

    public static char[] getPassword() {
        return getOrInitializeCache().password.clone();
    }

    private static boolean isPastKnownExpiry(CertCache certCache) {

        if (certCache == null
                || certCache.expiryDate == null) {

            return true;
        }

        LocalDate today =
                LocalDate.now(EXPIRY_ZONE);

        /*
         * Certificate is considered expired on the
         * configured expiry date.
         *
         * today >= expiryDate
         */
        return !today.isBefore(
                certCache.expiryDate
        );
    }

    private static CertCache getOrInitializeCache() {

        CertCache result = cache;

        /*
         * FAST PATH
         */
        if (result != null
                && !isPastKnownExpiry(result)) {

            int hitNumber =
                    CACHE_HIT_COUNT.incrementAndGet();

            LOGGER.fine(
                    "[CACHE HIT #" + hitNumber + "] "
                            + "Using existing valid certificate from cache."
            );

            return result;
        }

        /*
         * SLOW PATH
         *
         * Only one thread can refresh the certificate.
         */
        synchronized (CertLoader.class) {

            result = cache;

            /*
             * Double-check after acquiring the lock.
             */
            if (result != null
                    && !isPastKnownExpiry(result)) {

                int hitNumber =
                        CACHE_HIT_COUNT.incrementAndGet();

                LOGGER.info(
                        "[CACHE HIT #" + hitNumber + "] "
                                + "Another thread already loaded a valid certificate. "
                                + "Using cached certificate."
                );

                return result;
            }

            LOGGER.info(
                    "Certificate cache is empty or expired. "
                            + "Loading latest certificate from AWS Secrets Manager."
            );

            try {

                CertCache newCache =
                        fetchAndBuildCache();

                /*
                 * Replace cache only after successful
                 * validation and KeyStore initialization.
                 */
                cache = newCache;

                int fetchNumber =
                        FRESH_FETCH_COUNT.incrementAndGet();

                LOGGER.info(
                        "[FRESH FETCH #" + fetchNumber + "] "
                                + "Latest certificate loaded successfully. "
                                + "Cache updated. Expiry date="
                                + newCache.expiryDate
                );

                return newCache;

            } catch (Exception e) {

                LOGGER.log(
                        Level.SEVERE,
                        "Unable to load the latest certificate "
                                + "from AWS Secrets Manager.",
                        e
                );

                LocalDate today =
                        LocalDate.now(EXPIRY_ZONE);

                /*
                 * Stale fallback is allowed ONLY when the
                 * existing certificate is still actually valid.
                 */
                if (result != null
                        && result.expiryDate != null
                        && today.isBefore(result.expiryDate)) {

                    LOGGER.warning(
                            "Latest certificate could not be loaded, "
                                    + "but existing cached certificate is still valid. "
                                    + "Continuing with existing certificate."
                    );

                    return result;
                }

                LOGGER.severe(
                        "No valid certificate is available. "
                                + "The request cannot continue."
                );

                throw new RuntimeException(
                        "Certificate loading failed and "
                                + "no valid cached certificate is available.",
                        e
                );
            }
        }
    }

    private static CertCache fetchAndBuildCache() {

        LOGGER.info(
                "Fetching certificate secret from AWS Secrets Manager: "
                        + SECRET_NAME
        );

        String secretJson;

        try {

            GetSecretValueRequest request =
                    GetSecretValueRequest.builder()
                            .secretId(SECRET_NAME)
                            .build();

            secretJson =
                    SECRETS_CLIENT
                            .getSecretValue(request)
                            .secretString();

        } catch (SecretsManagerException e) {

            String errorMessage =
                    e.awsErrorDetails() != null
                            ? e.awsErrorDetails().errorMessage()
                            : e.getMessage();

            LOGGER.log(
                    Level.SEVERE,
                    "AWS Secrets Manager error while loading secret: "
                            + errorMessage,
                    e
            );

            throw new RuntimeException(
                    "Failed to fetch certificate secret from AWS",
                    e
            );
        }

        if (secretJson == null
                || secretJson.trim().isEmpty()) {

            LOGGER.severe(
                    "Secret string returned from AWS Secrets Manager "
                            + "is empty for: "
                            + SECRET_NAME
            );

            throw new IllegalStateException(
                    "Secret string returned from AWS Secrets Manager is empty."
            );
        }

        JsonNode certNode;
        JsonNode passNode;
        JsonNode expiryNode;

        try {

            JsonNode secret =
                    OBJECT_MAPPER.readTree(secretJson);

            if (secret == null
                    || !secret.isObject()) {

                throw new IllegalArgumentException(
                        "Secret content must be a valid JSON object."
                );
            }

            LOGGER.info(
                    "Using secret field names: "
                            + "cert_key=" + CERT_KEY
                            + ", password_key=" + PASSWORD_KEY
                            + ", expiry_key=" + EXPIRY_KEY
            );

            certNode =
                    secret.get(CERT_KEY);

            passNode =
                    secret.get(PASSWORD_KEY);

            expiryNode =
                    secret.get(EXPIRY_KEY);

        } catch (Exception e) {

            LOGGER.log(
                    Level.SEVERE,
                    "Failed to parse secret JSON for: "
                            + SECRET_NAME,
                    e
            );

            throw new RuntimeException(
                    "Failed to parse certificate secret JSON",
                    e
            );
        }

        /*
         * Validate certificate field.
         */
        if (certNode == null
                || certNode.isNull()
                || certNode.asText().trim().isEmpty()) {

            LOGGER.severe(
                    "Secret JSON missing or empty required field '"
                            + CERT_KEY
                            + "' for: "
                            + SECRET_NAME
            );

            throw new IllegalArgumentException(
                    "Secret JSON missing or empty required field: '"
                            + CERT_KEY
                            + "'"
            );
        }

        /*
         * Validate password field.
         */
        if (passNode == null
                || passNode.isNull()
                || passNode.asText().isEmpty()) {

            LOGGER.severe(
                    "Secret JSON missing or empty required field '"
                            + PASSWORD_KEY
                            + "' for: "
                            + SECRET_NAME
            );

            throw new IllegalArgumentException(
                    "Secret JSON missing or empty required field: '"
                            + PASSWORD_KEY
                            + "'"
            );
        }

        /*
         * IMPORTANT:
         * Validate expiry field before accessing it.
         *
         * This prevents a NullPointerException and gives
         * a clear production error.
         */
        if (expiryNode == null
                || expiryNode.isNull()
                || expiryNode.asText().trim().isEmpty()) {

            LOGGER.severe(
                    "Secret JSON missing or empty required field '"
                            + EXPIRY_KEY
                            + "' for: "
                            + SECRET_NAME
            );

            throw new IllegalArgumentException(
                    "Secret JSON missing or empty required field: '"
                            + EXPIRY_KEY
                            + "'"
            );
        }

        byte[] certBytes;

        try {

            certBytes =
                    Base64.getDecoder()
                            .decode(
                                    certNode.asText().trim()
                            );

        } catch (IllegalArgumentException e) {

            LOGGER.log(
                    Level.SEVERE,
                    "Certificate field '"
                            + CERT_KEY
                            + "' contains invalid Base64 data for: "
                            + SECRET_NAME,
                    e
            );

            throw new RuntimeException(
                    "Invalid Base64 certificate data",
                    e
            );
        }

        char[] password =
                passNode.asText().toCharArray();

        LocalDate expiryDate;

        try {

            /*
             * Expected ISO-8601 format:
             *
             * 2026-08-31
             */
            expiryDate =
                    LocalDate.parse(
                            expiryNode.asText().trim()
                    );

        } catch (Exception e) {

            LOGGER.log(
                    Level.SEVERE,
                    "Invalid expiry date in field '"
                            + EXPIRY_KEY
                            + "'. Expected ISO format yyyy-MM-dd. "
                            + "Secret: "
                            + SECRET_NAME,
                    e
            );

            throw new RuntimeException(
                    "Invalid certificate expiry date",
                    e
            );
        }

        KeyStore keyStore;

        try (
                ByteArrayInputStream inputStream =
                        new ByteArrayInputStream(certBytes)
        ) {

            keyStore =
                    KeyStore.getInstance(
                            KEYSTORE_TYPE
                    );

            keyStore.load(
                    inputStream,
                    password
            );

            LOGGER.info(
                    "CertLoader successfully initialized KeyStore "
                            + "with "
                            + keyStore.size()
                            + " alias(es)."
            );

        } catch (Exception e) {

            LOGGER.log(
                    Level.SEVERE,
                    "Failed to load and parse certificate KeyStore "
                            + "for: "
                            + SECRET_NAME,
                    e
            );

            throw new RuntimeException(
                    "Failed to initialize certificate KeyStore",
                    e
            );
        }

        return new CertCache(
                keyStore,
                password,
                expiryDate
        );
    }
}
