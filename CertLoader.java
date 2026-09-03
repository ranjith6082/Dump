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

private static final Logger logger = Logger.getLogger(CertLoader.class.getName());

// Secrets Manager secret: { "cert_base64": "...", "jks_password": "..." }
private static final String SECRET_NAME =
        System.getenv().getOrDefault("ADCB_Jks_Certificate", "ADCB_Jks_Certificate");

// SSM parameter holding the cert version marker (e.g. V1, V2), updated by the rotation pipeline.
private static final String SSM_VERSION_PARAM =
        System.getenv().getOrDefault("CERT_VERSION_SSM_PARAM", "/adcb/certloader/cert-version");

// Warm containers only re-check SSM once per TTL; within it, cache is served with no AWS calls.
private static final long VERSION_CHECK_TTL_MILLIS =
        Long.parseLong(System.getenv().getOrDefault("CERT_VERSION_CHECK_TTL_MS", "600000")); // 10 min

private static final Region REGION =
        Region.of(System.getenv().getOrDefault("AWS_REGION_NAME", "eu-central-1"));

private static final SecretsManagerClient secretsClient = SecretsManagerClient.builder()
        .region(REGION)
        .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(10))
                .apiCallAttemptTimeout(Duration.ofSeconds(5))
                .build())
        .build();

private static final SsmClient ssmClient = SsmClient.builder()
        .region(REGION)
        .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(5))
                .apiCallAttemptTimeout(Duration.ofSeconds(3))
                .build())
        .build();

private static final ObjectMapper objectMapper = new ObjectMapper();

private static volatile CertCache cache;
private static volatile long lastVersionCheckMillis = 0L;

// Observability counters, exposed via getters below.
private static final AtomicInteger cacheHitCount = new AtomicInteger(0);
private static final AtomicInteger freshFetchCount = new AtomicInteger(0);
private static final AtomicInteger versionCheckCount = new AtomicInteger(0);

private static final class CertCache {
    final KeyStore keyStore;
    final char[] password;
    final String version;

    CertCache(KeyStore keyStore, char[] password, String version) {
        this.keyStore = keyStore;
        this.password = password;
        this.version = version;
    }
}

// Cold start pre-warm. Best-effort: if it fails, the first request retries via getOrInitializeCache().
static {
    try {
        String version = getCurrentSsmVersion();
        if (version != null) {
            cache = fetchAndBuildCache(version);
            lastVersionCheckMillis = System.currentTimeMillis();
            freshFetchCount.incrementAndGet();
            logger.info("[COLD START] Certificate loaded, version=" + version);
        } else {
            logger.warning("[COLD START] SSM unavailable, will retry on first request");
        }
    } catch (Exception e) {
        logger.log(Level.WARNING, "[COLD START] Certificate load failed, will retry on first request", e);
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
    long now = System.currentTimeMillis();

    // Cache empty (cold start pre-warm failed, or first request on this container).
    if (result == null) {
        synchronized (CertLoader.class) {
            if (cache == null) {
                String version = getCurrentSsmVersion();
                versionCheckCount.incrementAndGet();
                if (version == null) {
                    throw new IllegalStateException(
                            "Certificate cache is empty and SSM version is unavailable");
                }
                logger.info("[CACHE MISS] Loading certificate, version=" + version);
                cache = fetchAndBuildCache(version);
                lastVersionCheckMillis = System.currentTimeMillis();
                freshFetchCount.incrementAndGet();
            }
            return cache;
        }
    }

    // Within TTL - serve cache, no AWS calls.
    if (now - lastVersionCheckMillis < VERSION_CHECK_TTL_MILLIS) {
        cacheHitCount.incrementAndGet();
        return result;
    }

    // TTL expired - only one thread checks SSM.
    synchronized (CertLoader.class) {
        result = cache;
        now = System.currentTimeMillis();

        if (now - lastVersionCheckMillis < VERSION_CHECK_TTL_MILLIS) {
            return result; // another thread already refreshed
        }

        // Set before the SSM call so a slow/failing SSM doesn't cause repeated calls per request.
        lastVersionCheckMillis = now;
        versionCheckCount.incrementAndGet();
        String currentVersion = getCurrentSsmVersion();

        if (currentVersion == null) {
            logger.warning("[SSM FAILED] Keeping existing certificate, version=" + result.version);
            return result;
        }

        if (currentVersion.equals(result.version)) {
            cacheHitCount.incrementAndGet();
            return result;
        }

        logger.info("[CERT CHANGED] old=" + result.version + " new=" + currentVersion);
        try {
            CertCache newCache = fetchAndBuildCache(currentVersion);
            // Replace the cache only after the new certificate is successfully loaded.
            cache = newCache;
            freshFetchCount.incrementAndGet();
            logger.info("[REFRESH SUCCESS] version=" + currentVersion);
            return newCache;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[REFRESH FAILED] Keeping old certificate, version=" + result.version, e);
            return result;
        }
    }
}

// Returns null on any failure - callers treat null as "no version info, don't force refetch".
private static String getCurrentSsmVersion() {
    try {
        GetParameterRequest request = GetParameterRequest.builder().name(SSM_VERSION_PARAM).build();
        String version = ssmClient.getParameter(request).parameter().value();
        return (version == null || version.isBlank()) ? null : version.trim();
    } catch (ParameterNotFoundException e) {
        logger.warning("SSM parameter not found: " + SSM_VERSION_PARAM);
        return null;
    } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to read SSM version parameter", e);
        return null;
    }
}

private static CertCache fetchAndBuildCache(String version) {
    String secretJson;
    try {
        secretJson = secretsClient.getSecretValue(
                GetSecretValueRequest.builder().secretId(SECRET_NAME).build()).secretString();
    } catch (SecretsManagerException e) {
        throw new RuntimeException("Failed to fetch certificate secret", e);
    }

    if (secretJson == null || secretJson.isBlank()) {
        throw new IllegalStateException("Secrets Manager returned empty secret");
    }

    JsonNode secret;
    JsonNode certNode;
    JsonNode passwordNode;
    try {
        secret = objectMapper.readTree(secretJson);
        certNode = secret.get("cert_base64");
        passwordNode = secret.get("jks_password");
    } catch (Exception e) {
        throw new RuntimeException("Failed to parse certificate secret JSON", e);
    }

    if (certNode == null || passwordNode == null) {
        throw new IllegalArgumentException("Secret missing cert_base64 or jks_password");
    }

    byte[] certBytes = Base64.getDecoder().decode(certNode.asText());
    char[] password = passwordNode.asText().toCharArray();

    try {
        String keyStoreType = System.getenv().getOrDefault("KEYSTORE_TYPE", "JKS");
        KeyStore keyStore = KeyStore.getInstance(keyStoreType);

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(certBytes)) {
            keyStore.load(inputStream, password);
        }

        if (keyStore.size() == 0) {
            throw new IllegalStateException("KeyStore contains no entries");
        }

        logger.info("[CERT LOADED] version=" + version + " aliases=" + keyStore.size());
        return new CertCache(keyStore, password, version);
    } catch (Exception e) {
        Arrays.fill(password, '\0');
        throw new RuntimeException("Failed to load certificate", e);
    }
}

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
