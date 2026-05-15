## 2024-05-24 - Avoid parsing large SharedPreferences JSON in resource interceptors
**Learning:** Frequent parsing of large JSON containing Base64 encoded Chrome extension payloads from SharedPreferences causes severe memory and CPU overhead. This is especially true when it's done repeatedly, such as on every resource request in `shouldInterceptRequest`.
**Action:** Use a memory cache for parsed plugins to prevent parsing on every resource request.
