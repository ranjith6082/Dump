```java id="wmx0j8"
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
```
