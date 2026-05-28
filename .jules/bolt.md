## 2024-05-18 - [Cache `getPlugins()` list parsing]
**Learning:** parsing the list of plugins from json stored in SharedPreferences frequently incurs memory and CPU overhead. Caching the parsed list, tied to the original JSON string, is highly effective, as long as it utilizes deep copy so as not to mutate the original cache on changes.
**Action:** When repeatedly reading complex structured data from SharedPreferences, use an in-memory cache tied to the raw string content for fast validation to prevent CPU overhead and memory allocations.
