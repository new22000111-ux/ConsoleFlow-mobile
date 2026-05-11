## 2024-05-18 - [Cache parsed SharedPreferences to avoid JSON re-parsing]
**Learning:** Parsing JSON from `SharedPreferences` on every getter call (e.g. `getPlugins()`) is a significant performance bottleneck, especially for lists of objects.
**Action:** Use an in-memory `@Volatile` cache to store the parsed object list and return a mutable copy (`.toMutableList()`) to preserve the original method signature while bypassing the expensive I/O and JSON parsing. Ensure the cache is updated whenever the underlying preferences are modified.
