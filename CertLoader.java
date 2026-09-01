```text
Util.java

// API request calls the certificate validation method

KeyStore ks = CertLoader.getKeyStore();
char[] password = CertLoader.getPassword();


COLD START / FIRST CLASS INITIALIZATION
=======================================

// When CertLoader class is initialized,
// the static block runs only once per container

static {

    // Pre-load certificate data into cache

    cache = fetchAndBuildCache();

}


// fetchAndBuildCache()

// Fetch certificate, password and expiry date
// from AWS Secrets Manager

// Validate required fields

// Decode Base64 certificate

// Load certificate into KeyStore

// Create cache containing:
// KeyStore + Password + Expiry Date



getOrInitializeCache(){

1. Read current cache

   CertCache result = cache;

   ↓


2. CONDITION 1: FAST PATH

   (Is cache available AND certificate is valid?)

   Valid means:

   (today < expiryDate)


   YES → Certificate is available and still valid

         // Use existing cache

         → return result


   NO  → Cache is empty
         OR expiry date is missing
         OR certificate is expired

         // Only then enter synchronized block

         → Enter synchronized block



   synchronized (CertLoader.class) {

       // Only one thread can refresh the cache at a time

       ↓


3. Read cache again

   result = cache;

   // Another thread may have refreshed the cache
   // while this thread was waiting for the lock

   ↓


4. CONDITION 2: CHECK CACHE AGAIN

   (Is cache available now AND certificate is valid?)


   YES → Another request may have already
         loaded a valid certificate while
         this request was waiting for the lock

         // Use the refreshed cache

         → return result


   NO  → Cache is still empty
         OR expiry date is missing
         OR certificate is expired

         // Load latest certificate

         → Fetch latest certificate from
           AWS Secrets Manager

         → fetchAndBuildCache()



       ↓


5. CONDITION 3: NEW CERTIFICATE LOAD

   (Was the new certificate loaded successfully?)


   YES → New certificate is successfully loaded

         // Replace old cache only after
         // successful certificate loading

         → Update cache

         cache = newCache;

         → return newCache


   NO  → Latest certificate loading failed

         // Try valid old cache as fallback

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

         // Continue using old valid cache

         → return result


   NO  → Old cache does not exist
         OR expiry date is missing
         OR old certificate is expired

         // Do not use expired certificate

         → No valid certificate is available

         → throw exception


   } // End synchronized block


} // End getOrInitializeCache()



Simple return summary
=====================

// Normal cache hit

CONDITION 1 YES
→ return result
→ Use existing valid cache


// Another thread already refreshed cache

CONDITION 2 YES
→ return result
→ Use refreshed cached certificate


// Latest certificate loaded successfully

CONDITION 3 YES
→ return newCache
→ Use newly fetched certificate


// Latest loading failed but old cache is valid

CONDITION 4 YES
→ return result
→ Use old cache as fallback


// No valid certificate available

CONDITION 4 NO
→ throw exception
→ Fail request
```
