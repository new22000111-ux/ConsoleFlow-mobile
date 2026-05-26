## 2024-05-26 - Cached plugins list returning deep copies to avoid CPU/memory overhead
**Learning:** Chrome extension ZIP payloads stored as Base64 encoded strings in SharedPreferences are causing overhead. To prevent memory/CPU overhead, `pluginsJson` must be cached in memory in `MainActivity.kt`.
**Action:** When accessing `prefsManager.pluginsJson` or parsing the JSON array, ensure that deep copies of the list are returned using a thread-safe wrapper (e.g., `@Volatile`) and that the original JSON string is tracked to validate if there's stale data.
