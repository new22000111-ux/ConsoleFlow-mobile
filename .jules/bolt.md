## 2024-05-24 - Expensive SharedPreferences Access on WebView interceptions
**Learning:** Frequent JSON parsing of heavy data (e.g., base64 zip payloads inside `SharedPreferences`) within `WebView` intercepts (`shouldInterceptRequest`) causes major CPU usage and blocked main threads, slowing down page loading tremendously.
**Action:** Always verify how often SharedPreferences data is accessed on hot code paths (e.g., every resource request) and implement in-memory caching if parsed dynamically to prevent massive bottlenecks.
