## 2026-05-23 - Cached Plugins in Memory
**Learning:** Parsing JSON from SharedPreferences for every getPlugins() call creates significant CPU/memory overhead in Android applications.
**Action:** When accessing frequently used list properties from SharedPreferences, cache them in memory using instance variables and invalidate the cache upon updates to the SharedPreferences entry.
