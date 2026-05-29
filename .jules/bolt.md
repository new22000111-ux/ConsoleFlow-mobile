## 2024-05-24 - [In-memory Cache for Plugins]
**Learning:** Parsing large JSON strings from SharedPreferences repeatedly is a massive CPU and memory bottleneck, particularly for plugins configuration that's accessed frequently during navigation and plugin evaluation.
**Action:** Implemented an in-memory cache tied to the raw string content (using an atomic `@Volatile Pair<String, List<BrowserPlugin>>`) to avoid race conditions and stale data while returning deep copies of the parsed list.
