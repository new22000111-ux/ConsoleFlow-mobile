## 2024-05-18 - Caching SharedPreferences JSON Parsing in ConsoleFlow
**Learning:** Frequent JSON parsing from SharedPreferences can cause significant CPU overhead and generate many short-lived objects. `getPlugins()` was doing this on every invocation.
**Action:** Always consider caching the parsed results of expensive configuration structures from SharedPreferences (like JSON objects/arrays), especially if they are frequently accessed during the lifecycle. Cache invalidation happens directly when modifying the underlying data.
