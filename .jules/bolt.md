## 2024-05-19 - SharedPreferences caching needs atomic validation
**Learning:** Parsing JSON from `SharedPreferences` on every call inside `getPlugins()` was a huge performance bottleneck because it caused heavy CPU usage and memory allocations. Caching it requires atomic validation of the underlying JSON string and making deep copies on retrieval to avoid returning references that get unexpectedly mutated.
**Action:** When repeatedly reading from SharedPreferences, particularly complex structured data, use an in-memory cache tied to the raw string content for fast validation.
