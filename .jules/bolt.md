## 2024-05-24 - Parse overhead optimization
**Learning:** Parsing JSON payload from SharedPreferences on every request (especially when Chrome extension properties are heavily accessed) causes severe memory and CPU overhead bottleneck.
**Action:** Always cache the parsed objects in memory and update the cache upon modifications, rather than reading and parsing from SharedPreferences repeatedly.
