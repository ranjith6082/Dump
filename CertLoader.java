```java id="tf8otq"
private static boolean isPastKnownExpiry(CertCache cache) {

    if (cache == null || cache.expiryDate == null) {
        return true;
    }

    LocalDate today = LocalDate.now(EXPIRY_ZONE);

    /*
     * Returns true when:
     *
     * today >= expiryDate
     *
     * Example:
     *
     * Today      = 31-12-2026
     * ExpiryDate = 31-12-2026
     *
     * Result = true
     */
    return !today.isBefore(cache.expiryDate);
}


private static CertCache getOrInitializeCache() {

    /*
     * Read the current cache.
     */
    CertCache result = cache;


    /*
     * FAST PATH
     *
     * Condition 1:
     * Cache must exist.
     *
     * Condition 2:
     * Certificate expiry date must NOT be reached.
     *
     * If both conditions are satisfied,
     * use the cached certificate.
     */
    if (result != null && !isPastKnownExpiry(result)) {

        int hitNumber = cacheHitCount.incrementAndGet();

        logger.info(
                "[CACHE HIT] Using cached cert/password - "
                        + "no Secrets Manager call made (hit #"
                        + hitNumber + ")"
        );

        return result;
    }


    /*
     * SLOW PATH
     *
     * Cache is NULL OR certificate expiry date has been reached.
     *
     * Synchronization prevents multiple threads inside
     * the SAME Lambda container from loading the certificate
     * at the same time.
     */
    synchronized (CertLoader.class) {

        /*
         * Read cache again after getting the lock.
         *
         * Another request may have loaded a valid certificate
         * while this request was waiting for the lock.
         */
        result = cache;


        /*
         * DOUBLE CHECK
         *
         * If another thread already loaded a valid certificate,
         * use it and do not call Secrets Manager again.
         */
        if (result != null && !isPastKnownExpiry(result)) {

            int hitNumber = cacheHitCount.incrementAndGet();

            logger.info(
                    "[CACHE HIT AFTER LOCK] Using valid certificate "
                            + "already available in cache (hit #"
                            + hitNumber + ")"
            );

            return result;
        }


        logger.info(
                "[CACHE MISS] Cache is empty or expiry date reached - "
                        + "fetching latest certificate from Secrets Manager "
                        + "(attempt #"
                        + (freshFetchCount.get() + 1)
                        + ")"
        );


        try {

            /*
             * IMPORTANT:
             *
             * Load the complete new certificate first.
             *
             * fetchAndBuildCache() should:
             *
             * 1. Read secret from Secrets Manager
             * 2. Validate certificate
             * 3. Validate password
             * 4. Validate expiry date
             * 5. Decode Base64
             * 6. Load KeyStore
             *
             * Existing cache is NOT changed if any step fails.
             */
            CertCache newCache = fetchAndBuildCache();


            /*
             * Replace the old cache only after the
             * new certificate was successfully loaded.
             *
             * volatile cache reference makes the new
             * cache visible to other threads.
             */
            cache = newCache;


            int fetchNumber =
                    freshFetchCount.incrementAndGet();


            logger.info(
                    "[CACHE MISS] Fresh fetch complete - "
                            + "cache successfully updated. "
                            + "expiryDate="
                            + newCache.expiryDate
                            + ", fetch #"
                            + fetchNumber
            );


            return newCache;


        } catch (Exception e) {

            logger.log(
                    Level.SEVERE,
                    "[CACHE FETCH FAILED] Failed to fetch "
                            + "certificate from Secrets Manager.",
                    e
            );


            /*
             * Read today's date again for fallback validation.
             */
            LocalDate today =
                    LocalDate.now(EXPIRY_ZONE);


            /*
             * If an old cache exists and is still valid,
             * use it temporarily.
             *
             * IMPORTANT:
             * Do NOT use this fallback if your security policy
             * requires immediate failure when certificate
             * refresh fails.
             */
            if (result != null
                    && result.expiryDate != null
                    && today.isBefore(result.expiryDate)) {

                logger.warning(
                        "[CACHE FALLBACK] Latest certificate could not "
                                + "be loaded, but existing certificate "
                                + "is still valid. Using existing cache."
                );

                return result;
            }


            /*
             * Cache does not exist OR cached certificate
             * is expired.
             *
             * Do not use an expired certificate.
             */
            throw new RuntimeException(
                    "Failed to load certificate from Secrets Manager "
                            + "and no valid cached certificate is available.",
                    e
            );
        }
    }
}
```
