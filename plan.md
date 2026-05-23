1. **Cache `getPlugins()` in `MainActivity.kt`**
   - Add a private field `private var cachedPlugins: MutableList<BrowserPlugin>? = null` to cache the parsed list of plugins in memory.
   - Update `getPlugins()` to check if `cachedPlugins` is populated, returning `cachedPlugins?.toMutableList()` to prevent redundant parsing of `prefsManager.pluginsJson`. If not cached, populate `cachedPlugins`.
   - Update `savePlugins()` and the "Clear All" logic to update `cachedPlugins` whenever `prefsManager.pluginsJson` is modified.

2. **Complete pre-commit steps**
   - Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.

3. **Submit the change**
   - Submit the branch with a title "⚡ Bolt: [performance improvement]" and details including What, Why, Impact, and Measurement.
