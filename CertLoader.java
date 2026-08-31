```java
private static boolean isPastKnownExpiry(CertCache cache) {

    if (cache == null || cache.expiryDate == null) {
        return true;
    }

    LocalDate today = LocalDate.now(EXPIRY_ZONE);

    // Refresh when today is equal to or after the expiry date.
    return !today.isBefore(cache.expiryDate);
}


private static CertCache getOrInitializeCache() {

    CertCache result = cache;

    /*
     * Cache is available and certificate has not reached
     * the expiry date.
     */
    if (result != null && !isPastKnownExpiry(result)) {

        cacheHitCount.incrementAndGet();

        logger.info(
                "Using the certificate from cache."
        );

        return result;
    }


    /*
     * Cache is empty or the certificate has reached
     * its expiry date.
     */
    synchronized (CertLoader.class) {

        // Check the cache again after acquiring the lock.
        result = cache;


        /*
         * Another request may have loaded a valid certificate
         * while this request was waiting for the lock.
         */
        if (result != null && !isPastKnownExpiry(result)) {

            cacheHitCount.incrementAndGet();

            logger.info(
                    "Using the certificate from cache after checking again."
            );

            return result;
        }


        logger.info(
                "Loading the latest certificate from Secrets Manager."
        );


        try {

            /*
             * Load and validate the complete certificate before
             * replacing the existing cache.
             */
            CertCache newCache = fetchAndBuildCache();


            /*
             * Update the cache only after successful loading.
             */
            cache = newCache;

            freshFetchCount.incrementAndGet();


            logger.info(
                    "Certificate loaded successfully and cache updated. "
                            + "Expiry date: "
                            + newCache.expiryDate
            );


            return newCache;


        } catch (Exception e) {

            logger.log(
                    Level.SEVERE,
                    "Unable to load the latest certificate from Secrets Manager.",
                    e
            );


            /*
             * Use the existing certificate only when it is
             * still valid.
             */
            LocalDate today =
                    LocalDate.now(EXPIRY_ZONE);


            if (result != null
                    && result.expiryDate != null
                    && today.isBefore(result.expiryDate)) {

                logger.warning(
                        "Latest certificate could not be loaded. "
                                + "Using the existing valid certificate."
                );

                return result;
            }


            /*
             * Do not use an expired certificate.
             */
            throw new RuntimeException(
                    "Certificate could not be loaded and no valid "
                            + "certificate is available.",
                    e
            );
        }
    }
}
```
