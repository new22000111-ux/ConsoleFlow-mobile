## 2025-02-28 - Cache pluginsJson parsing in memory
**Learning:** Parsing the `pluginsJson` from SharedPreferences for every URL load on Android creates severe memory allocations and CPU overhead, especially as the number of Chrome extensions grows. An in-memory atomic cache with a deep copy resolves this.
**Action:** When repeatedly reading complex structured data from SharedPreferences, use an in-memory cache tied to the raw string content for fast validation to prevent CPU overhead and memory allocations.
