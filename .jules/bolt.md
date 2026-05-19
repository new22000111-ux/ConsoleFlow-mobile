## 2024-06-25 - Avoid frequent SharedPreferences JSON parsing
**Learning:** Parsing large JSON strings (like Chrome extension arrays) from `SharedPreferences` on every plugin-related call (e.g., in WebView callbacks) causes significant memory and CPU overhead.
**Action:** Always cache the parsed data structure in memory and sync it with `SharedPreferences` only when the underlying data is modified.
