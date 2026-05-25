## 2024-06-25 - SharedPreferences JSON Caching in Android
**Learning:** Parsing large JSON strings (like Chrome extension payloads in `pluginsJson`) repeatedly from `SharedPreferences` causes unnecessary CPU and memory overhead on Android. However, a naive memory cache is dangerous if it doesn't track the underlying data.
**Action:** Always use an in-memory cache when repeatedly accessing large, complex data structures stored as JSON strings in `SharedPreferences`. To avoid stale data, the cache must track both the parsed objects and the original JSON string, and validate `cachedJson == currentJson` before returning the cached objects.

Additionally, ensure thread-safety when modifying cache variables by grouping them atomically (e.g. within a `@Volatile` data class wrapper), and always return a deep copy (e.g., `list.map { it.copy() }.toMutableList()`) to prevent shared state mutations from callers.
