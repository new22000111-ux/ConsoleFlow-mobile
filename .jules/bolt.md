## 2024-05-10 - Expensive JSON Parsing on Main Thread
**Learning:** `getPlugins()` was reading and parsing `pluginsJson` (which can contain huge base64 encoded zip files for Chrome extensions) from `SharedPreferences` on every URL change and every JS bridge API call, causing massive garbage collection pauses and main thread blocking.
**Action:** Caching parsed objects in memory until they are explicitly modified is essential when storing large datasets in SharedPreferences, especially when accessed frequently.
