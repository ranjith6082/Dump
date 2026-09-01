getOrInitializeCache(){

1. Read current cache

   CertCache result = cache;

   ↓


2. CONDITION 1: FAST PATH

   (Is cache available AND certificate is valid?)

   Valid means:

   (today < expiryDate)


   YES → Certificate is available and still valid

         → return result


   NO  → Cache is empty
         OR expiry date is missing
         OR certificate is expired

         → Enter synchronized block



   synchronized (CertLoader.class) {

       ↓


3. Read cache again

   result = cache;

   ↓


4. CONDITION 2: CHECK CACHE AGAIN

   (Is cache available now AND certificate is valid?)


   YES → Another request may have already
         loaded a valid certificate while
         this request was waiting for the lock

         → return result


   NO  → Cache is still empty
         OR expiry date is missing
         OR certificate is expired

         → Fetch latest certificate from
           AWS Secrets Manager

         → fetchAndBuildCache()



       ↓


5. CONDITION 3: NEW CERTIFICATE LOAD

   (Was the new certificate loaded successfully?)


   YES → New certificate is successfully loaded

         → Update cache

         cache = newCache;

         → return newCache


   NO  → Latest certificate loading failed

         → Check old cached certificate



       ↓


6. CONDITION 4: OLD CACHE FALLBACK

   (Is old cached certificate still valid?)


   Valid means:

   (Old cache exists
    AND
    expiry date exists
    AND
    today < expiryDate)


   YES → Old certificate is still valid

         → Continue using old cached certificate

         → return result


   NO  → Old cache does not exist
         OR expiry date is missing
         OR old certificate is expired

         → No valid certificate is available

         → throw exception


   } // End synchronized block


} // End getOrInitializeCache()
Simple return summary
CONDITION 1 YES
→ return result
→ Use existing valid cache


CONDITION 2 YES
→ return result
→ Another request already refreshed the cache


CONDITION 3 YES
→ return newCache
→ Use newly fetched certificate


CONDITION 4 YES
→ return result
→ Use old cache as fallback


CONDITION 4 NO
→ throw exception
→ No valid certificate is available
