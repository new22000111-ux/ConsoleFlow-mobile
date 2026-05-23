package space.karrarnazim.ConsoleFlow

import android.Manifest
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Patterns
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {
    data class BrowserTab(
        val id: Int,
        var title: String,
        var url: String,
        var state: Bundle? = null
    )

    data class BrowserPlugin(
        val id: String,
        val name: String,
        val matchPattern: String,
        val script: String,
        val popupPath: String? = null,
        val sidePanelPath: String? = null,
        val optionsPage: String? = null,
        val packageZipBase64: String? = null,
        val packageFileName: String? = null,
        val enabled: Boolean,
        val deepAccess: Boolean,
        val allowToast: Boolean = true,
        val allowClipboard: Boolean = false,
        val allowShare: Boolean = false,
        val allowOpenExternal: Boolean = false,
        val allowReadUrl: Boolean = true
    )

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var textUrl: EditText
    private lateinit var btnBookmark: ImageView
    private lateinit var imgSearchEngine: ImageView
    private lateinit var findBar: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var pluginSidePanelHost: FrameLayout
    private lateinit var tabsContainer: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var btnNewTab: TextView
    private lateinit var btnTabSwitcher: TextView

    private lateinit var prefsManager: PrefsManager
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val extensionDownloadClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPermissionRequest: PermissionRequest? = null
    private var chromeStoreCompatMode = false
    private var lastChromeStorePromptUrl: String? = null
    private val browserTabs = mutableListOf<BrowserTab>()
    private var activeTabIndex = -1
    private var nextTabId = 1
    private var activePluginPopup: PopupWindow? = null
    private var activeSidePanelWebView: WebView? = null
    private var activeSidePanelPluginId: String? = null
    private val pluginLastError = mutableMapOf<String, String>()
    private val pluginMessageCatalogs = ConcurrentHashMap<String, String>()
    private val pluginBackgroundRuntimes = ConcurrentHashMap<String, WebView>()
    private val pluginBackgroundRuntimeReady = ConcurrentHashMap.newKeySet<String>()
    private val pendingRuntimeMessageTargets = ConcurrentHashMap<String, WebView>()

    private val HOME_URL = "file:///android_asset/home.html"
    private val ERROR_URL = "file:///android_asset/error.html"
    private val CHROME_STORE_URL = "https://chromewebstore.google.com/"

    // Performance optimization: Cache parsed plugins to avoid redundant SharedPreferences reads
    // and expensive JSON parsing on every getPlugins() call. Impact: O(1) memory lookup vs O(N) parsing
    private var cachedPlugins: MutableList<BrowserPlugin>? = null

    // Domains that trigger CAPTCHA when intercepted by OkHttp — skip interception for these
    private val NO_INTERCEPT_DOMAINS = listOf(
        "google.com", "googleapis.com", "gstatic.com", "accounts.google.com",
        "bing.com", "microsoft.com", "live.com",
        "duckduckgo.com",
        "search.brave.com",
        "yahoo.com", "yandex.com"
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) webPermissionRequest?.grant(webPermissionRequest?.resources)
        else webPermissionRequest?.deny()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefsManager = PrefsManager(this)

        initViews()
        setupWebView()
        setupListeners()

        if (savedInstanceState != null) {
            browserTabs.add(BrowserTab(nextTabId++, "Restored Tab", webView.url ?: HOME_URL))
            activeTabIndex = 0
            webView.restoreState(savedInstanceState)
            updateTabsUi()
        } else {
            createNewTab(HOME_URL)
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> hideCustomView()
                pluginSidePanelHost.visibility == View.VISIBLE -> closePluginSidePanel()
                findBar.visibility == View.VISIBLE -> findBar.visibility = View.GONE
                webView.canGoBack() -> webView.goBack()
                else -> finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveActiveTabState()
        webView.saveState(outState)
    }

    override fun onDestroy() {
        activePluginPopup?.dismiss()
        closePluginSidePanel()
        pluginBackgroundRuntimes.values.forEach { it.destroy() }
        pluginBackgroundRuntimes.clear()
        super.onDestroy()
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        textUrl = findViewById(R.id.textUrl)
        btnBookmark = findViewById(R.id.btnBookmark)
        imgSearchEngine = findViewById(R.id.imgSearchEngine)
        findBar = findViewById(R.id.findBar)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        pluginSidePanelHost = findViewById(R.id.pluginSidePanelHost)
        tabsContainer = findViewById(R.id.tabsContainer)
        tabScroll = findViewById(R.id.tabScroll)
        btnNewTab = findViewById(R.id.btnNewTab)
        btnTabSwitcher = findViewById(R.id.btnTabSwitcher)

        // Load current search engine favicon
        updateSearchEngineIcon()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        updateUserAgent()

        webView.addJavascriptInterface(SearchBridge(), "Android")
        webView.addJavascriptInterface(PluginBridge().apply { sourceWebView = webView }, "ConsoleFlowHost")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("https://chrome.google.com/webstore")) {
                    val rewritten = url.replace(
                        "https://chrome.google.com/webstore",
                        "https://chromewebstore.google.com"
                    )
                    view.loadUrl(rewritten)
                    return true
                }
                if (url.startsWith("http:") || url.startsWith("https:") || url.startsWith("file:")) return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (e: Exception) { true }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                // Don't show file:// paths in the search bar
                if (url == HOME_URL || url?.startsWith(ERROR_URL) == true) {
                    textUrl.setText("")
                } else {
                    textUrl.setText(url)
                }
                updateActiveTab(url = url ?: HOME_URL, title = "Loading…")
                updateBookmarkIcon(url ?: "")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.INVISIBLE
                url?.let {
                    updateActiveTab(url = it, title = view?.title?.takeIf { title -> title.isNotBlank() } ?: readableTabTitle(it))
                    if (it != HOME_URL) prefsManager.addHistory(view?.title ?: "Unknown", it)
                }
                // Keep the developer console available without relying on cross-origin XHR.
                injectConsole(show = false)
                if (!url.isNullOrBlank()) {
                    injectExtensionPageApiIfNeeded(url)
                    handleChromeStoreCompatibility(url)
                    maybeShowNativeInstallPrompt(url)
                    runPluginsForUrl(url)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!url.isNullOrBlank()) {
                    handleChromeStoreCompatibility(url)
                    maybeShowNativeInstallPrompt(url)
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                val host = request.url.host ?: ""

                serveLocalBrowserRequest(request)?.let { return it }

                // Skip interception for search engines / major sites — prevents CAPTCHA
                if (NO_INTERCEPT_DOMAINS.any { host.endsWith(it) }) return null

                // Smart HTML injection for Eruda (captures early console errors)
                if (request.isForMainFrame && request.method == "GET" && url.startsWith("http")) {
                    try {
                        val reqBuilder = Request.Builder().url(url)
                        request.requestHeaders.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
                        val cookie = CookieManager.getInstance().getCookie(url)
                        if (cookie != null) reqBuilder.addHeader("Cookie", cookie)

                        val response = client.newCall(reqBuilder.build()).execute()
                        val contentType = response.header("Content-Type", "") ?: ""

                        if (contentType.contains("text/html")) {
                            var html = response.body?.string() ?: ""
                            val injection = "<script src=\"https://eruda.local/eruda.js\"></script><script>eruda.init();</script>"
                            val customJs = if (prefsManager.customJs.isNotEmpty()) "<script>${prefsManager.customJs}</script>" else ""

                            html = html.replaceFirst("<head>", "<head>$injection$customJs", ignoreCase = true)
                            val inputStream = ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                            return WebResourceResponse("text/html", response.header("Content-Encoding", "utf-8"), response.code, "OK", response.headers.toMap(), inputStream)
                        }
                    } catch (e: Exception) {
                        return null
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    view.loadUrl("$ERROR_URL?url=${URLEncoder.encode(request.url.toString(), "UTF-8")}")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                AlertDialog.Builder(this@MainActivity, R.style.DarkDialog)
                    .setTitle("SSL Certificate Error")
                    .setMessage("The site's security certificate is not trusted. Continue anyway?")
                    .setPositiveButton("Continue") { _, _ -> handler.proceed() }
                    .setNegativeButton("Go Back") { _, _ -> handler.cancel() }
                    .show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }

            override fun onHideCustomView() {
                fullscreenContainer.removeView(customView)
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                customView = null
                customViewCallback?.onCustomViewHidden()
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                webPermissionRequest = request
                val androidPerms = mutableListOf<String>()
                request.resources.forEach {
                    when (it) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> androidPerms.add(Manifest.permission.RECORD_AUDIO)
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> androidPerms.add(Manifest.permission.CAMERA)
                    }
                }
                if (androidPerms.isNotEmpty()) requestPermissionLauncher.launch(androidPerms.toTypedArray())
                else request.grant(request.resources)
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                val cookies = CookieManager.getInstance().getCookie(url)
                addRequestHeader("cookie", cookies)
                addRequestHeader("User-Agent", userAgent)
                setDescription("Downloading file...")
                setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                allowScanningByMediaScanner()
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimetype)
                )
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(applicationContext, "Downloading File", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        // Only allow pull-to-refresh when WebView is truly at top (not during upward scroll)
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipeRefresh.setOnRefreshListener { webView.reload() }

        textUrl.setOnEditorActionListener { _, _, _ ->
            val input = textUrl.text.toString().trim()
            val finalUrl = when {
                input.startsWith("http://") || input.startsWith("https://") -> input
                Patterns.WEB_URL.matcher(input).matches() -> "https://$input"
                else -> prefsManager.searchEngine + URLEncoder.encode(input, "utf-8")
            }
            webView.loadUrl(finalUrl)
            hideKeyboard()
            true
        }

        textUrl.setOnLongClickListener {
            val popup = PopupMenu(this, textUrl)
            popup.menu.add("Copy URL").setOnMenuItemClickListener {
                val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("URL", webView.url))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                true
            }
            popup.menu.add("Share URL").setOnMenuItemClickListener {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, webView.url)
                }
                startActivity(Intent.createChooser(share, "Share URL"))
                true
            }
            popup.show()
            true
        }

        findViewById<View>(R.id.goBack).setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<View>(R.id.goForward).setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<View>(R.id.goHome).setOnClickListener { webView.loadUrl(HOME_URL) }
        findViewById<View>(R.id.btnConsole).setOnClickListener { toggleConsole() }
        btnNewTab.setOnClickListener { createNewTab(HOME_URL) }
        btnTabSwitcher.setOnClickListener { showTabSwitcher() }

        btnBookmark.setOnClickListener {
            val url = webView.url ?: return@setOnClickListener
            val title = webView.title ?: "Bookmark"
            val added = prefsManager.toggleBookmark(title, url)
            updateBookmarkIcon(url)
            Toast.makeText(this, if (added) "Bookmarked" else "Removed", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        val inputFind = findViewById<EditText>(R.id.findInput)
        val tvMatches = findViewById<TextView>(R.id.findMatches)

        webView.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            tvMatches.text = if (numberOfMatches > 0) "${activeMatchOrdinal + 1}/$numberOfMatches" else "0/0"
        }

        inputFind.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { webView.findAllAsync(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        findViewById<View>(R.id.btnFindNext).setOnClickListener { webView.findNext(true) }
        findViewById<View>(R.id.btnFindPrev).setOnClickListener { webView.findNext(false) }
        findViewById<View>(R.id.btnFindClose).setOnClickListener {
            findBar.visibility = View.GONE
            webView.clearMatches()
            hideKeyboard()
        }
    }

    private fun createNewTab(url: String) {
        saveActiveTabState()
        browserTabs.add(BrowserTab(nextTabId++, "New Tab", url))
        activeTabIndex = browserTabs.lastIndex
        updateTabsUi()
        webView.loadUrl(url)
    }

    private fun switchToTab(index: Int) {
        if (index == activeTabIndex || index !in browserTabs.indices) return
        saveActiveTabState()
        activeTabIndex = index
        updateTabsUi()
        val tab = browserTabs[index]
        val restored = tab.state?.let { webView.restoreState(it) } != null
        if (!restored) webView.loadUrl(tab.url.ifBlank { HOME_URL })
        textUrl.setText(if (tab.url == HOME_URL || tab.url.startsWith(ERROR_URL)) "" else tab.url)
    }

    private fun closeTab(index: Int) {
        if (index !in browserTabs.indices) return
        if (browserTabs.size == 1) {
            browserTabs[0].apply {
                title = "New Tab"
                url = HOME_URL
                state = null
            }
            activeTabIndex = 0
            updateTabsUi()
            webView.loadUrl(HOME_URL)
            return
        }

        browserTabs.removeAt(index)
        activeTabIndex = when {
            activeTabIndex > index -> activeTabIndex - 1
            activeTabIndex >= browserTabs.size -> browserTabs.lastIndex
            else -> activeTabIndex
        }
        updateTabsUi()
        val tab = browserTabs[activeTabIndex]
        val restored = tab.state?.let { webView.restoreState(it) } != null
        if (!restored) webView.loadUrl(tab.url.ifBlank { HOME_URL })
    }

    private fun saveActiveTabState() {
        if (activeTabIndex !in browserTabs.indices) return
        val state = Bundle()
        webView.saveState(state)
        browserTabs[activeTabIndex].apply {
            this.state = state
            url = webView.url ?: url
            title = webView.title?.takeIf { it.isNotBlank() } ?: title
        }
    }

    private fun updateActiveTab(url: String? = null, title: String? = null) {
        if (activeTabIndex !in browserTabs.indices) return
        browserTabs[activeTabIndex].apply {
            if (!url.isNullOrBlank()) this.url = url
            if (!title.isNullOrBlank()) this.title = title
        }
        updateTabsUi()
    }

    private fun updateTabsUi() {
        tabsContainer.removeAllViews()
        btnTabSwitcher.text = browserTabs.size.toString()
        browserTabs.forEachIndexed { index, tab ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(4), 0)
                background = getDrawable(
                    if (index == activeTabIndex) R.drawable.browser_tab_active_bg
                    else R.drawable.browser_tab_inactive_bg
                )
                setOnClickListener { switchToTab(index) }
            }
            val title = TextView(this).apply {
                text = tab.title.ifBlank { readableTabTitle(tab.url) }.take(18)
                setTextColor(if (index == activeTabIndex) 0xFFFFFFFF.toInt() else 0xFFB7C3D4.toInt())
                textSize = 13f
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
            }
            val close = TextView(this).apply {
                text = "×"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 20f
                gravity = Gravity.CENTER
                setOnClickListener { closeTab(index) }
            }
            chip.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            chip.addView(close, LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.MATCH_PARENT))
            val lp = LinearLayout.LayoutParams(dp(152), dp(34)).apply {
                marginEnd = dp(6)
            }
            tabsContainer.addView(chip, lp)
        }
        tabScroll.post { tabScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }
    }

    private fun showTabSwitcher() {
        val titles = browserTabs.mapIndexed { index, tab ->
            val prefix = if (index == activeTabIndex) "✓ " else ""
            "$prefix${tab.title.ifBlank { readableTabTitle(tab.url) }}\n${tab.url}"
        }.toTypedArray()
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Tabs (${browserTabs.size})")
            .setItems(titles) { _, which -> switchToTab(which) }
            .setPositiveButton("New Tab") { _, _ -> createNewTab(HOME_URL) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun readableTabTitle(url: String): String {
        if (url == HOME_URL) return "Home"
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        return uri?.host?.removePrefix("www.") ?: "New Tab"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ── Custom dark menu dialog ────────────────────────────────────────────────
    private fun showMenu(anchor: View) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val menuView = layoutInflater.inflate(R.layout.layout_main_menu, null)
        dialog.setContentView(menuView)

        // Transparent dialog window positioned near the top-right (below the 3-dot button)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            // Disable entry animation for instant appearance
            setWindowAnimations(0)
            val lp = attributes
            lp.gravity = Gravity.TOP or Gravity.END
            lp.x = resources.getDimensionPixelSize(R.dimen.menu_margin)
            lp.y = resources.getDimensionPixelSize(R.dimen.menu_top_offset)
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            attributes = lp
        }

        // Update desktop mode check mark
        val checkView = menuView.findViewById<android.widget.TextView>(R.id.menuDesktopModeCheck)
        checkView.visibility = if (prefsManager.desktopMode) View.VISIBLE else View.GONE

        menuView.findViewById<View>(R.id.menuBookmarks).setOnClickListener {
            dialog.dismiss(); showBookmarksDialog()
        }
        menuView.findViewById<View>(R.id.menuHistory).setOnClickListener {
            dialog.dismiss(); showHistoryDialog()
        }
        menuView.findViewById<View>(R.id.menuFindInPage).setOnClickListener {
            dialog.dismiss(); findBar.visibility = View.VISIBLE
        }
        menuView.findViewById<View>(R.id.menuChromeStore).setOnClickListener {
            dialog.dismiss()
            openChromeStore()
        }
        menuView.findViewById<View>(R.id.menuToggleConsole).setOnClickListener {
            dialog.dismiss(); toggleConsole()
        }
        menuView.findViewById<View>(R.id.menuRunJavaScript).setOnClickListener {
            dialog.dismiss(); showRunJavaScriptDialog()
        }
        menuView.findViewById<View>(R.id.menuDesktopMode).setOnClickListener {
            dialog.dismiss()
            prefsManager.desktopMode = !prefsManager.desktopMode
            updateUserAgent()
            val currentUrl = webView.url
            if (!currentUrl.isNullOrEmpty() && currentUrl != HOME_URL) {
                webView.loadUrl(currentUrl)
            }
        }
        menuView.findViewById<View>(R.id.menuSettings).setOnClickListener {
            dialog.dismiss(); showSettingsDialog()
        }
        menuView.findViewById<View>(R.id.menuClearData).setOnClickListener {
            dialog.dismiss(); clearData()
        }

        dialog.show()
    }

    private fun toggleConsole() {
        injectConsole(show = true) { message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun injectConsole(show: Boolean, onComplete: ((String) -> Unit)? = null) {
        val shouldToggle = show.toString()
        webView.evaluateJavascript(
            """
            (function(){
                try {
                    var shouldToggle = $shouldToggle;
                    if (!window.eruda) {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', 'https://eruda.local/eruda.js', false);
                        xhr.send(null);
                        if (xhr.status && xhr.status >= 400) return 'Console failed to load (' + xhr.status + ')';
                        (0, eval)(xhr.responseText);
                        if (!window.eruda) return 'Console failed to load';
                        eruda.init({
                            defaults: {
                                displaySize: 55,
                                transparency: 0.96
                            }
                        });
                        window.__erudaLoaded = true;
                        window.__consoleFlowConsoleVisible = false;
                    }

                    if (!shouldToggle) {
                        if (window.eruda && eruda.hide) eruda.hide();
                        window.__consoleFlowConsoleVisible = false;
                        return 'Console ready';
                    }

                    if (window.__consoleFlowConsoleVisible === true) {
                        eruda.hide();
                        window.__consoleFlowConsoleVisible = false;
                        return 'Console hidden';
                    }

                    eruda.show();
                    window.__consoleFlowConsoleVisible = true;
                    return 'Console opened';
                } catch (e) {
                    return 'Console error: ' + (e && e.message ? e.message : e);
                }
            })();
            """.trimIndent()
        ) { result ->
            onComplete?.invoke(result?.trim('"') ?: "Done")
        }
    }

    private fun showRunJavaScriptDialog() {
        val input = EditText(this).apply {
            hint = "console.log(document.title)"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding(32, 24, 32, 24)
            isSingleLine = false
            minLines = 5
        }

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Run JavaScript")
            .setView(input)
            .setPositiveButton("Run") { _, _ ->
                val script = input.text.toString().trim()
                if (script.isEmpty()) return@setPositiveButton
                webView.evaluateJavascript(script) { result ->
                    val output = result?.take(180) ?: "null"
                    Toast.makeText(this, "Result: $output", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openChromeStore() {
        chromeStoreCompatMode = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        webView.loadUrl(CHROME_STORE_URL)
    }

    private fun handleChromeStoreCompatibility(url: String) {
        val isChromeStore = url.contains("chromewebstore.google.com")
        if (isChromeStore) {
            if (!chromeStoreCompatMode) {
                chromeStoreCompatMode = true
                webView.settings.userAgentString =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            }
            injectChromeStoreInstallButton()
        } else if (chromeStoreCompatMode) {
            chromeStoreCompatMode = false
            updateUserAgent()
        }
    }

    private fun injectChromeStoreInstallButton() {
        webView.evaluateJavascript(
            """
            (function(){
              if(window.__cfStoreInstallInjected){return;}
              var match=location.pathname.match(/([a-z]{32})(?:\/|$)/);
              if(!match){return;}
              window.__cfStoreInstallInjected=true;
              var btn=document.createElement('button');
              btn.textContent='Install in ConsoleFlow';
              btn.style.position='fixed';
              btn.style.bottom='16px';
              btn.style.right='16px';
              btn.style.zIndex='2147483647';
              btn.style.background='#1f6feb';
              btn.style.color='#fff';
              btn.style.border='0';
              btn.style.padding='12px 14px';
              btn.style.borderRadius='10px';
              btn.style.fontSize='13px';
              btn.style.fontWeight='600';
              btn.style.boxShadow='0 8px 18px rgba(0,0,0,.35)';
              btn.onclick=function(){
                if(window.Android && Android.installFromStore){
                  Android.installFromStore(location.href);
                }else{
                  alert('Installer bridge is unavailable');
                }
              };
              document.body.appendChild(btn);
            })();
            """.trimIndent(),
            null
        )
    }

    private fun maybeShowNativeInstallPrompt(url: String) {
        if (!url.contains("chromewebstore.google.com")) return
        val extensionId = extractChromeExtensionId(url) ?: return
        if (lastChromeStorePromptUrl == url) return
        lastChromeStorePromptUrl = url

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Install Extension")
            .setMessage("Install this Chrome extension directly in ConsoleFlow?\n\nID: $extensionId")
            .setPositiveButton("Install Now") { _, _ -> installChromeStoreExtension(url) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun extractChromeExtensionId(url: String): String? {
        return ChromeExtensionInstaller.extractExtensionId(url)
    }

    private fun showBookmarksDialog() {
        val bookmarks = prefsManager.getBookmarks()
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "No bookmarks saved", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = bookmarks.map { it.first.ifEmpty { it.second } }.toTypedArray()
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Bookmarks")
            .setItems(titles) { _, which -> webView.loadUrl(bookmarks[which].second) }
            .setNeutralButton("Clear All") { _, _ ->
                getSharedPreferences("ConsoleFlowPrefs", Context.MODE_PRIVATE)
                    .edit().remove("bookmarks").apply()
                updateBookmarkIcon(webView.url ?: "")
                Toast.makeText(this, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showHistoryDialog() {
        val history = prefsManager.getHistory()
        if (history.isEmpty()) {
            Toast.makeText(this, "No history", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = history.map { it.first.ifEmpty { it.second } }.toTypedArray()
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("History")
            .setItems(titles) { _, which -> webView.loadUrl(history[which].second) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = layoutInflater.inflate(R.layout.layout_settings, null)
        dialog.setContentView(view)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setWindowAnimations(0)
            val lp = attributes
            lp.gravity = Gravity.BOTTOM
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            attributes = lp
        }

        // Set current search engine label
        val engines = arrayOf("Google", "DuckDuckGo", "Bing", "Brave")
        val urls = arrayOf(
            "https://www.google.com/search?q=",
            "https://duckduckgo.com/?q=",
            "https://www.bing.com/search?q=",
            "https://search.brave.com/search?q="
        )
        val currentEngineLabel = when {
            prefsManager.searchEngine.contains("google")     -> "Google"
            prefsManager.searchEngine.contains("duckduckgo") -> "DuckDuckGo"
            prefsManager.searchEngine.contains("bing")       -> "Bing"
            prefsManager.searchEngine.contains("brave")      -> "Brave"
            else -> "Google"
        }
        view.findViewById<TextView>(R.id.settingSearchEngineValue).text = currentEngineLabel

        // Desktop mode switch
        val switchDesktop = view.findViewById<android.widget.Switch>(R.id.switchDesktopMode)
        switchDesktop.isChecked = prefsManager.desktopMode
        switchDesktop.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.desktopMode = isChecked
            updateUserAgent()
            val currentUrl = webView.url
            if (!currentUrl.isNullOrEmpty() && currentUrl != HOME_URL) {
                webView.loadUrl(currentUrl)
            }
        }
        view.findViewById<View>(R.id.settingDesktopMode).setOnClickListener {
            switchDesktop.isChecked = !switchDesktop.isChecked
        }

        // Search engine picker
        view.findViewById<View>(R.id.settingSearchEngine).setOnClickListener {
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Select Search Engine")
                .setItems(engines) { _, which ->
                    prefsManager.searchEngine = urls[which]
                    view.findViewById<TextView>(R.id.settingSearchEngineValue).text = engines[which]
                    updateSearchEngineIcon()
                    Toast.makeText(this, "${engines[which]} selected", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // Custom JS editor
        view.findViewById<View>(R.id.settingCustomJs).setOnClickListener {
            val input = android.widget.EditText(this).apply {
                setText(prefsManager.customJs)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF1A1A1A.toInt())
                setPadding(32, 24, 32, 24)
                hint = "// Your JavaScript here..."
                setHintTextColor(0xFF555555.toInt())
                isSingleLine = false
                minLines = 4
            }
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Custom JavaScript")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    prefsManager.customJs = input.text.toString()
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        view.findViewById<View>(R.id.settingPlugins).setOnClickListener {
            showPluginsDialog()
        }

        // Clear data
        view.findViewById<View>(R.id.settingClearData).setOnClickListener {
            dialog.dismiss(); clearData()
        }

        dialog.show()
    }

    private fun showPluginsDialog() {
        showPluginManagerPage()
    }

    private fun showPluginManagerPage() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F1620.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(10), 0)
            setBackgroundColor(0xFF141D29.toInt())
        }
        val title = TextView(this).apply {
            text = "Plugin Manager"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val add = TextView(this).apply {
            text = "+"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 28f
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.browser_icon_button_bg)
            setOnClickListener { showPluginEditorPage(null) { dialog.dismiss(); showPluginManagerPage() } }
        }
        val close = TextView(this).apply {
            text = "×"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 28f
            gravity = Gravity.CENTER
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(add, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(56)))
        root.addView(header)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        val install = pluginManagerButton("Install from Store URL") {
            dialog.dismiss()
            showInstallFromChromeStoreDialog()
        }
        val clear = pluginManagerButton("Clear All") {
            prefsManager.pluginsJson = "[]"
            cachedPlugins = mutableListOf()
            clearPluginPackages()
            pluginBackgroundRuntimes.values.forEach { it.destroy() }
            pluginBackgroundRuntimes.clear()
            closePluginSidePanel()
            Toast.makeText(this, "All plugins removed", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            showPluginManagerPage()
        }
        actions.addView(install, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        actions.addView(clear, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(actions)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(16))
        }
        val plugins = getPlugins()
        if (plugins.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No plugins installed yet."
                setTextColor(0xFFB7C3D4.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, dp(48))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            plugins.forEach { plugin ->
                list.addView(createPluginManagerRow(plugin, dialog), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) })
            }
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun pluginManagerButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.browser_icon_button_bg)
            setOnClickListener { onClick() }
        }
    }

    private fun createPluginManagerRow(plugin: BrowserPlugin, parentDialog: Dialog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = getDrawable(R.drawable.browser_tab_inactive_bg)
        }
        val titleLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val name = TextView(this).apply {
            text = plugin.name
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        val status = TextView(this).apply {
            text = if (plugin.enabled) "ON" else "OFF"
            setTextColor(if (plugin.enabled) 0xFF7CFFB2.toInt() else 0xFFFF8A8A.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        }
        titleLine.addView(name, LinearLayout.LayoutParams(0, dp(28), 1f))
        titleLine.addView(status, LinearLayout.LayoutParams(dp(52), dp(28)))
        row.addView(titleLine)
        row.addView(TextView(this).apply {
            text = plugin.matchPattern
            setTextColor(0xFF8A93A2.toInt())
            textSize = 12f
            maxLines = 1
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        val run = pluginManagerButton("Run") { runPluginFully(plugin) }
        val edit = pluginManagerButton("Edit") { parentDialog.dismiss(); showPluginEditorPage(plugin) { showPluginManagerPage() } }
        val files = pluginManagerButton("Files") { parentDialog.dismiss(); showPluginFilesPage(plugin) { showPluginManagerPage() } }
        val more = pluginManagerButton("More") { showPluginActions(plugin) }
        buttons.addView(run, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
        buttons.addView(edit, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
        buttons.addView(files, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
        buttons.addView(more, LinearLayout.LayoutParams(0, dp(40), 1f))
        row.addView(buttons)
        return row
    }

    private fun showPluginFilesPage(plugin: BrowserPlugin, onDone: (() -> Unit)? = null) {
        val zipBytes = readPluginPackageBytes(plugin)
        if (zipBytes == null) {
            Toast.makeText(this, "Plugin package files are missing", Toast.LENGTH_SHORT).show()
            onDone?.invoke()
            return
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F1620.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(10), 0)
            setBackgroundColor(0xFF141D29.toInt())
        }
        val title = TextView(this).apply {
            text = "${plugin.name} Files"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        val close = TextView(this).apply {
            text = "×"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 28f
            gravity = Gravity.CENTER
            setOnClickListener { dialog.dismiss(); onDone?.invoke() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(56)))
        root.addView(header)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(16))
        }
        ChromeExtensionInstaller.listFilePaths(zipBytes)
            .filter { isEditablePluginFile(it) }
            .forEach { path ->
                val row = TextView(this).apply {
                    text = path
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    setPadding(dp(14), 0, dp(14), 0)
                    gravity = Gravity.CENTER_VERTICAL
                    background = getDrawable(R.drawable.browser_tab_inactive_bg)
                    setOnClickListener {
                        showPluginFileEditor(plugin, path) {
                            dialog.dismiss()
                            showPluginFilesPage(findPlugin(plugin.id) ?: plugin, onDone)
                        }
                    }
                }
                list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(8) })
            }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun isEditablePluginFile(path: String): Boolean {
        return path.endsWith(".js", true) || path.endsWith(".html", true) || path.endsWith(".css", true) ||
                path.endsWith(".json", true) || path.endsWith(".txt", true)
    }

    private fun showPluginFileEditor(plugin: BrowserPlugin, path: String, onDone: (() -> Unit)? = null) {
        val zipBytes = readPluginPackageBytes(plugin) ?: return
        val content = ChromeExtensionInstaller.extractTextFileFromZip(zipBytes, path) ?: ""
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0F1620.toInt()) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(8), 0); setBackgroundColor(0xFF141D29.toInt()) }
        val title = TextView(this).apply { text = path; setTextColor(0xFFFFFFFF.toInt()); textSize = 16f; maxLines = 1 }
        val editor = pluginEditText("", content, singleLine = false).apply { gravity = Gravity.TOP or Gravity.START; textSize = 13f }
        val save = pluginManagerButton("Save") {
            val currentPlugin = findPlugin(plugin.id) ?: plugin
            val currentZip = readPluginPackageBytes(currentPlugin) ?: return@pluginManagerButton
            val updatedZip = ChromeExtensionInstaller.replaceTextFileInZip(currentZip, path, editor.text.toString())
            val fileName = writePluginPackage(currentPlugin.id, updatedZip)
            upsertPlugin(currentPlugin.copy(packageFileName = fileName, packageZipBase64 = null))
            pluginMessageCatalogs.remove(currentPlugin.id)
            Toast.makeText(this, "File saved", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onDone?.invoke()
        }
        val close = TextView(this).apply { text = "×"; setTextColor(0xFFFFFFFF.toInt()); textSize = 28f; gravity = Gravity.CENTER; setOnClickListener { dialog.dismiss(); onDone?.invoke() } }
        header.addView(title, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(save, LinearLayout.LayoutParams(dp(84), dp(42)).apply { marginEnd = dp(8) })
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(56)))
        root.addView(header)
        root.addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun showInstallFromChromeStoreDialog() {
        val input = EditText(this).apply {
            hint = "https://chromewebstore.google.com/detail/.../<extension-id>"
            setText(webView.url ?: "")
        }
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Install from Chrome Store")
            .setMessage("Paste extension page URL. ConsoleFlow will convert content scripts into a plugin.")
            .setView(input)
            .setPositiveButton("Install") { _, _ ->
                val url = input.text.toString().trim()
                installChromeStoreExtension(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installChromeStoreExtension(pageUrl: String) {
        val extensionId = extractChromeExtensionId(pageUrl)
        if (extensionId.isNullOrBlank()) {
            Toast.makeText(this, "Invalid Chrome extension URL", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Downloading extension…", Toast.LENGTH_SHORT).show()
        val progressDialog = AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Installing Plugin")
            .setMessage("Preparing download…")
            .setCancelable(false)
            .create()
        progressDialog.show()

        Thread {
            try {
                val updateUrl = ChromeExtensionInstaller.buildCrxDownloadUrl(extensionId)
                var responseBody: ByteArray? = null
                var lastError: Exception? = null
                for (attempt in 1..3) {
                    try {
                        runOnUiThread { progressDialog.setMessage("Downloading CRX package… (attempt $attempt/3)") }
                        val response = extensionDownloadClient.newCall(Request.Builder().url(updateUrl).build()).execute()
                        if (!response.isSuccessful) throw IllegalStateException("Download failed (${response.code})")
                        responseBody = response.body?.bytes()
                        if (responseBody != null) break
                    } catch (e: Exception) {
                        lastError = e
                        Thread.sleep((attempt * 900).toLong())
                    }
                }
                val crxBytes = responseBody ?: throw IllegalStateException(lastError?.message ?: "No data returned")
                runOnUiThread { progressDialog.setMessage("Parsing extension manifest…") }
                val zipBytes = ChromeExtensionInstaller.extractZipFromCrx(crxBytes)
                val payload = ChromeExtensionInstaller.parseCrxPayload(extensionId, zipBytes)
                val plugin = BrowserPlugin(
                    id = payload.extensionId,
                    name = payload.name,
                    matchPattern = payload.matchPattern,
                    script = payload.script,
                    popupPath = payload.popupPath,
                    sidePanelPath = payload.sidePanelPath,
                    optionsPage = payload.optionsPage,
                    packageFileName = writePluginPackage(payload.extensionId, zipBytes),
                    enabled = true,
                    deepAccess = false,
                    allowToast = true,
                    allowClipboard = false,
                    allowShare = false,
                    allowOpenExternal = false,
                    allowReadUrl = true
                )
                upsertPlugin(plugin)

                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(
                        this,
                        "Installed: ${plugin.name}. Open Plugin Manager to edit permissions.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showPluginActions(plugin: BrowserPlugin) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add((if (plugin.enabled) "Disable" else "Enable") to {
            upsertPlugin(plugin.copy(enabled = !plugin.enabled))
            if (plugin.enabled) {
                pluginBackgroundRuntimes.remove(plugin.id)?.destroy()
                if (activeSidePanelPluginId == plugin.id) closePluginSidePanel()
            }
            Toast.makeText(this, "Plugin updated", Toast.LENGTH_SHORT).show()
        })
        actions.add("Edit" to { showPluginEditor(plugin) })
        actions.add("Run Best UI" to { runPluginFully(plugin) })
        if (!plugin.popupPath.isNullOrBlank()) actions.add("Open Action Popup" to { showPluginPopup(plugin, plugin.popupPath) })
        if (!plugin.sidePanelPath.isNullOrBlank()) actions.add("Open Side Panel" to { showPluginSidePanel(plugin, plugin.sidePanelPath) })
        if (!plugin.optionsPage.isNullOrBlank()) actions.add("Open Options Page" to { openPluginPageInTab(plugin, plugin.optionsPage) })
        actions.add("Permissions" to { showPluginPermissionsDialog(plugin) })
        actions.add("View Last Error" to {
            val errorText = pluginLastError[plugin.id] ?: "No runtime errors recorded for this plugin."
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("${plugin.name} - Last Error")
                .setMessage(errorText)
                .setPositiveButton("OK", null)
                .show()
        })
        actions.add("Delete" to {
            deletePluginPackage(plugin)
            removePlugin(plugin.id)
            pluginBackgroundRuntimes.remove(plugin.id)?.destroy()
            pluginLastError.remove(plugin.id)
            pluginMessageCatalogs.remove(plugin.id)
            if (activeSidePanelPluginId == plugin.id) closePluginSidePanel()
            Toast.makeText(this, "Plugin deleted", Toast.LENGTH_SHORT).show()
        })

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle(plugin.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second.invoke() }
            .show()
    }

    private fun runPluginFully(plugin: BrowserPlugin) {
        if (!plugin.enabled) {
            Toast.makeText(this, "Plugin is disabled. Enable it first.", Toast.LENGTH_SHORT).show()
            return
        }

        ensureBackgroundRuntime(plugin)
        webView.url?.let { currentUrl ->
            runSinglePluginForUrl(plugin, currentUrl)
        }
        when {
            !plugin.sidePanelPath.isNullOrBlank() -> {
                showPluginSidePanel(plugin, plugin.sidePanelPath)
                Toast.makeText(this, "Side panel opened: ${plugin.name}", Toast.LENGTH_SHORT).show()
            }
            !plugin.popupPath.isNullOrBlank() -> {
                showPluginPopup(plugin, plugin.popupPath)
                Toast.makeText(this, "Popup opened: ${plugin.name}", Toast.LENGTH_SHORT).show()
            }
            !plugin.optionsPage.isNullOrBlank() -> {
                openPluginPageInTab(plugin, plugin.optionsPage)
                Toast.makeText(this, "Options page opened: ${plugin.name}", Toast.LENGTH_SHORT).show()
            }
            else -> {
                firePluginActionClicked()
                Toast.makeText(this, "Plugin started: ${plugin.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensureBackgroundRuntime(plugin: BrowserPlugin) {
        if (pluginBackgroundRuntimes.containsKey(plugin.id)) return
        val zipBytes = readPluginPackageBytes(plugin) ?: return
        val manifest = ChromeExtensionInstaller.readManifest(zipBytes) ?: return
        val backgroundPaths = ChromeExtensionInstaller.collectBackgroundScriptPaths(manifest)
        if (backgroundPaths.isEmpty()) return

        val sourceBuilder = StringBuilder()
        backgroundPaths.forEach { path ->
            val script = ChromeExtensionInstaller.extractTextFileFromZip(zipBytes, path)
            if (!script.isNullOrBlank()) {
                sourceBuilder.append("\n/* ").append(path).append(" */\n")
                sourceBuilder.append(script).append("\n")
            }
        }
        if (sourceBuilder.isBlank()) return

        val runtimeWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            val bridge = PluginBridge()
            addJavascriptInterface(SearchBridge(), "Android")
            addJavascriptInterface(bridge, "ConsoleFlowHost")
            bridge.sourceWebView = this
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val wrapped = buildString {
                        append("(function(){")
                        append(buildChromeCompatLayer(plugin.id))
                        append(buildPluginApiBootstrap(plugin))
                        append("try{")
                        append(sourceBuilder.toString())
                        append(";return JSON.stringify({ok:true});")
                        append("}catch(e){return JSON.stringify({ok:false,error:String(e)});}")
                        append("})();")
                    }
                    view?.evaluateJavascript(wrapped) { result ->
                        val normalized = result?.trim('"') ?: ""
                        if (normalized.contains("\"ok\":false")) {
                            pluginLastError[plugin.id] = "Background runtime: $normalized"
                        } else {
                            pluginBackgroundRuntimeReady.add(plugin.id)
                        }
                    }
                }
            }
            loadDataWithBaseURL(
                "https://chrome-extension.local/${plugin.id}/",
                "<html><body></body></html>",
                "text/html",
                "utf-8",
                null
            )
        }
        pluginBackgroundRuntimes[plugin.id] = runtimeWebView
    }

    private fun firePluginActionClicked() {
        webView.evaluateJavascript(
            "(function(){try{if(window.__cfExtBus&&window.__cfExtBus.listeners&&window.__cfExtBus.listeners['action:onClicked']){(window.__cfExtBus.listeners['action:onClicked']||[]).forEach(function(fn){try{fn({id:1,url:location.href,title:document.title||'',active:true,currentWindow:true});}catch(e){}});}}catch(e){}})();",
            null
        )
    }

    private fun serveLocalBrowserRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val host = request.url.host ?: ""

        val isHttpsExtension = host == "chrome-extension.local"
        val isChromeExtension = request.url.scheme == "chrome-extension"
        if (isHttpsExtension || isChromeExtension) {
            val extensionId = if (isChromeExtension) host else request.url.pathSegments.firstOrNull().orEmpty()
            val pathSegments = if (isChromeExtension) request.url.pathSegments else request.url.pathSegments.drop(1)
            val path = URLDecoder.decode(pathSegments.joinToString("/"), "UTF-8")
            if (extensionId.isNotBlank() && path.isNotBlank()) {
                val plugin = findPlugin(extensionId)
                val zipBytes = plugin?.let { readPluginPackageBytes(it) }
                if (zipBytes != null) {
                    return try {
                        val fileBytes = ChromeExtensionInstaller.extractFileFromZip(zipBytes, path)
                        if (fileBytes != null) {
                            val mimeType = guessMimeType(path)
                            val responseBytes = if (mimeType == "text/html" && plugin != null) {
                                injectExtensionBootstrapIntoHtml(
                                    fileBytes.toString(Charsets.UTF_8),
                                    plugin,
                                    "window.close=function(){if(window.ConsoleFlowHost)ConsoleFlowHost.closeExtensionSurfaceFor(${JSONObject.quote(plugin.id)});};"
                                ).toByteArray(Charsets.UTF_8)
                            } else fileBytes
                            WebResourceResponse(
                                mimeType,
                                "utf-8",
                                ByteArrayInputStream(responseBytes)
                            ).apply {
                                responseHeaders = mapOf(
                                    "Access-Control-Allow-Origin" to "*",
                                    "Cache-Control" to "no-cache"
                                )
                            }
                        } else {
                            WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                404,
                                "Not Found",
                                mapOf("Access-Control-Allow-Origin" to "*"),
                                ByteArrayInputStream("Extension file not found: $path".toByteArray())
                            )
                        }
                    } catch (e: Exception) {
                        WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            500,
                            "Extension Error",
                            mapOf("Access-Control-Allow-Origin" to "*"),
                            ByteArrayInputStream("Extension load error: ${e.message}".toByteArray())
                        )
                    }
                }
            }
        }

        if (url == "https://eruda.local/eruda.js") {
            return try {
                val stream = assets.open("eruda.js")
                WebResourceResponse("application/javascript", "utf-8", stream).apply {
                    responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                }
            } catch (e: Exception) { null }
        }

        return null
    }

    private fun injectExtensionBootstrapIntoHtml(html: String, plugin: BrowserPlugin, closeScript: String): String {
        val bootstrap = "<script>${buildChromeCompatLayer(plugin.id)}${buildPluginApiBootstrap(plugin)}$closeScript</script>"
        val cleaned = html
            .replace(Regex("<meta[^>]+http-equiv=[\\\"']Content-Security-Policy[\\\"'][^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<meta[^>]+content=[\\\"'][^\\\"']*script-src[^\\\"']*[\\\"'][^>]*>", RegexOption.IGNORE_CASE), "")
        return when {
            cleaned.contains("<head", ignoreCase = true) -> cleaned.replaceFirst(
                Regex("<head([^>]*)>", RegexOption.IGNORE_CASE),
                "<head$1>$bootstrap"
            )
            cleaned.contains("<html", ignoreCase = true) -> cleaned.replaceFirst(
                Regex("<html([^>]*)>", RegexOption.IGNORE_CASE),
                "<html$1><head>$bootstrap</head>"
            )
            else -> "$bootstrap$cleaned"
        }
    }

    private fun normalizeExtensionPath(plugin: BrowserPlugin, path: String?): String {
        val raw = path?.trim().orEmpty()
        val httpsPrefix = "https://chrome-extension.local/${plugin.id}/"
        val chromePrefix = "chrome-extension://${plugin.id}/"
        return when {
            raw.startsWith(httpsPrefix) -> raw.removePrefix(httpsPrefix)
            raw.startsWith(chromePrefix) -> raw.removePrefix(chromePrefix)
            else -> raw.trimStart('/')
        }
    }

    private fun buildExtensionPageUrl(plugin: BrowserPlugin, path: String?): String {
        val cleanPath = normalizeExtensionPath(plugin, path)
        return "https://chrome-extension.local/${plugin.id}/$cleanPath"
    }

    private fun ensurePluginPackage(plugin: BrowserPlugin): Boolean {
        if (readPluginPackageBytes(plugin) == null) {
            Toast.makeText(this, "Plugin package files are missing", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun proxyExtensionNetworkRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        if (request.url.host == "chrome-extension.local" || request.url.host == "eruda.local") return null
        if (request.method != "GET") return null

        return try {
            val proxied = Request.Builder().url(url).apply {
                request.requestHeaders.forEach { (name, value) ->
                    if (!name.equals("Host", ignoreCase = true)) addHeader(name, value)
                }
            }.build()
            val response = extensionDownloadClient.newCall(proxied).execute()
            val contentType = response.header("Content-Type") ?: guessMimeType(url)
            WebResourceResponse(
                contentType.substringBefore(';'),
                response.header("Content-Encoding") ?: "utf-8",
                response.code,
                response.message.ifBlank { "OK" },
                response.headers.toMap().toMutableMap().apply {
                    this["Access-Control-Allow-Origin"] = "*"
                    this["Access-Control-Allow-Headers"] = "*"
                    this["Access-Control-Allow-Methods"] = "GET,POST,PUT,DELETE,OPTIONS"
                },
                response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun createExtensionPageWebView(plugin: BrowserPlugin, closeScript: String): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setBackgroundColor(0xFF111820.toInt())
            val bridge = PluginBridge()
            addJavascriptInterface(SearchBridge(), "Android")
            addJavascriptInterface(bridge, "ConsoleFlowHost")
            bridge.sourceWebView = this
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return serveLocalBrowserRequest(request)
                        ?: proxyExtensionNetworkRequest(request)
                        ?: super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val nextUrl = request.url.toString()
                    return when {
                        nextUrl.startsWith("chrome-extension://${plugin.id}/") -> {
                            view.loadUrl(buildExtensionPageUrl(plugin, nextUrl))
                            true
                        }
                        nextUrl.startsWith("https://chrome-extension.local/") || nextUrl.startsWith("file:") -> false
                        nextUrl.startsWith("http://") || nextUrl.startsWith("https://") -> {
                            createNewTab(nextUrl)
                            activePluginPopup?.dismiss()
                            true
                        }
                        else -> try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(nextUrl)))
                            true
                        } catch (_: Exception) {
                            true
                        }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val bootstrap = buildString {
                        append(buildChromeCompatLayer(plugin.id))
                        append(buildPluginApiBootstrap(plugin))
                        append(closeScript)
                    }
                    view?.evaluateJavascript(bootstrap, null)
                }
            }
        }
    }

    private fun showPluginPopup(plugin: BrowserPlugin, path: String? = plugin.popupPath) {
        val popupPath = path?.takeIf { it.isNotBlank() }
        if (popupPath.isNullOrBlank()) {
            Toast.makeText(this, "This plugin has no popup UI", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ensurePluginPackage(plugin)) return

        activePluginPopup?.dismiss()
        val popupWebView = createExtensionPageWebView(
            plugin,
            "window.close=function(){if(window.ConsoleFlowHost)ConsoleFlowHost.closePopupFor(${JSONObject.quote(plugin.id)});};"
        ).apply {
            loadUrl(buildExtensionPageUrl(plugin, popupPath))
        }

        val width = minOf(dp(440), (resources.displayMetrics.widthPixels * 0.94f).toInt()).coerceAtLeast(dp(260))
        val expandedHeight = minOf(dp(600), (resources.displayMetrics.heightPixels * 0.78f).toInt()).coerceAtLeast(dp(240))
        val minimizedHeight = dp(48)
        var popupX = dp(10)
        var popupY = dp(106)
        var minimized = false

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.browser_popup_bg)
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
            setBackgroundColor(0xFF151E2A.toInt())
        }
        val title = TextView(this).apply {
            text = plugin.name
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            maxLines = 1
        }
        val move = TextView(this).apply {
            text = "⇄"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
        }
        val minimize = TextView(this).apply {
            text = "–"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val close = TextView(this).apply {
            text = "×"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 24f
            gravity = Gravity.CENTER
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(46), 1f))
        header.addView(move, LinearLayout.LayoutParams(dp(44), dp(46)))
        header.addView(minimize, LinearLayout.LayoutParams(dp(44), dp(46)))
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(46)))
        container.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
        container.addView(popupWebView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        activePluginPopup = PopupWindow(container, width, expandedHeight, false).apply {
            isOutsideTouchable = false
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = dp(10).toFloat()
            setOnDismissListener {
                popupWebView.destroy()
                if (activePluginPopup === this) activePluginPopup = null
            }
            showAtLocation(webView, Gravity.TOP or Gravity.END, popupX, popupY)
        }

        var dragStartRawX = 0f
        var dragStartRawY = 0f
        var dragStartX = 0
        var dragStartY = 0
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartX = popupX
                    dragStartY = popupY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    popupX = (dragStartX - (event.rawX - dragStartRawX).toInt()).coerceAtLeast(0)
                    popupY = (dragStartY + (event.rawY - dragStartRawY).toInt()).coerceAtLeast(dp(64))
                    activePluginPopup?.update(popupX, popupY, width, if (minimized) minimizedHeight else expandedHeight)
                    true
                }
                else -> false
            }
        }
        move.setOnClickListener {
            popupX = if (popupX < resources.displayMetrics.widthPixels / 4) dp(10) else resources.displayMetrics.widthPixels - width - dp(10)
            activePluginPopup?.update(popupX, popupY, width, if (minimized) minimizedHeight else expandedHeight)
        }
        minimize.setOnClickListener {
            minimized = !minimized
            popupWebView.visibility = if (minimized) View.GONE else View.VISIBLE
            minimize.text = if (minimized) "□" else "–"
            activePluginPopup?.update(popupX, popupY, width, if (minimized) minimizedHeight else expandedHeight)
        }
        close.setOnClickListener { activePluginPopup?.dismiss() }
    }

    private fun showPluginSidePanel(plugin: BrowserPlugin, path: String? = plugin.sidePanelPath ?: plugin.popupPath ?: plugin.optionsPage) {
        val panelPath = path?.takeIf { it.isNotBlank() }
        if (panelPath.isNullOrBlank()) {
            Toast.makeText(this, "This plugin has no side panel page", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ensurePluginPackage(plugin)) return

        closePluginSidePanel()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.browser_popup_bg)
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            setBackgroundColor(0xFF151E2A.toInt())
        }
        val title = TextView(this).apply {
            text = plugin.name
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            maxLines = 1
        }
        val close = TextView(this).apply {
            text = "×"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 24f
            gravity = Gravity.CENTER
            setOnClickListener { closePluginSidePanel() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(38), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(38)))

        val panelWebView = createExtensionPageWebView(
            plugin,
            "window.close=function(){if(window.ConsoleFlowHost)ConsoleFlowHost.closeSidePanelFor(${JSONObject.quote(plugin.id)});};"
        ).apply {
            loadUrl(buildExtensionPageUrl(plugin, panelPath))
        }
        panel.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(38)
        ))
        panel.addView(panelWebView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        activeSidePanelWebView = panelWebView
        activeSidePanelPluginId = plugin.id
        pluginSidePanelHost.removeAllViews()
        pluginSidePanelHost.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        pluginSidePanelHost.visibility = View.VISIBLE
    }

    private fun closePluginSidePanel() {
        activeSidePanelWebView?.destroy()
        activeSidePanelWebView = null
        activeSidePanelPluginId = null
        pluginSidePanelHost.removeAllViews()
        pluginSidePanelHost.visibility = View.GONE
    }

    private fun openPluginPageInTab(plugin: BrowserPlugin, path: String? = plugin.optionsPage ?: plugin.popupPath ?: plugin.sidePanelPath) {
        val pagePath = path?.takeIf { it.isNotBlank() }
        if (pagePath.isNullOrBlank()) {
            Toast.makeText(this, "This plugin has no extension page", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ensurePluginPackage(plugin)) return
        createNewTab(buildExtensionPageUrl(plugin, pagePath))
    }

    private fun showPluginPermissionsDialog(plugin: BrowserPlugin) {
        val labels = arrayOf(
            "Toast Notifications",
            "Clipboard Write",
            "Share Text",
            "Open External Links",
            "Read Current URL"
        )
        val checked = booleanArrayOf(
            plugin.allowToast,
            plugin.allowClipboard,
            plugin.allowShare,
            plugin.allowOpenExternal,
            plugin.allowReadUrl
        )
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("${plugin.name} Permissions")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                upsertPlugin(
                    plugin.copy(
                        allowToast = checked[0],
                        allowClipboard = checked[1],
                        allowShare = checked[2],
                        allowOpenExternal = checked[3],
                        allowReadUrl = checked[4]
                    )
                )
                Toast.makeText(this, "Permissions updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPluginEditor(existing: BrowserPlugin?) {
        showPluginEditorPage(existing)
    }

    private fun showPluginEditorPage(existing: BrowserPlugin?, onDone: (() -> Unit)? = null) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F1620.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(10), 0)
            setBackgroundColor(0xFF141D29.toInt())
        }
        val title = TextView(this).apply {
            text = if (existing == null) "Create Plugin" else "Edit Plugin"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val save = pluginManagerButton("Save") {
            // replaced below after inputs are created
        }
        val close = TextView(this).apply {
            text = "×"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 28f
            gravity = Gravity.CENTER
            setOnClickListener { dialog.dismiss(); onDone?.invoke() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(save, LinearLayout.LayoutParams(dp(84), dp(42)).apply { marginEnd = dp(8) })
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(56)))
        root.addView(header)

        val scroll = ScrollView(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
        }
        val nameInput = pluginEditText("Plugin name", existing?.name ?: "", singleLine = true)
        val matchInput = pluginEditText("Match host/path (example: github.com or *)", existing?.matchPattern ?: "*", singleLine = true)
        val scriptInput = pluginEditText(
            "JavaScript code (ConsoleFlowApi.toast/copyToClipboard/shareText/openExternal/currentUrl)",
            existing?.script ?: "",
            singleLine = false
        ).apply {
            minLines = 14
            gravity = Gravity.TOP or Gravity.START
        }
        val deepAccessSwitch = Switch(this).apply {
            text = "Deep host access (Clipboard/Share/Open app/Toast)"
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = existing?.deepAccess ?: false
        }
        val enabledSwitch = Switch(this).apply {
            text = "Enabled"
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = existing?.enabled ?: true
        }
        form.addView(nameInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply { bottomMargin = dp(10) })
        form.addView(matchInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply { bottomMargin = dp(10) })
        form.addView(scriptInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)).apply { bottomMargin = dp(12) })
        form.addView(deepAccessSwitch)
        form.addView(enabledSwitch)
        scroll.addView(form)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        save.setOnClickListener {
            val name = nameInput.text.toString().trim().ifEmpty { "Plugin" }
            val match = matchInput.text.toString().trim().ifEmpty { "*" }
            val script = scriptInput.text.toString()
            if (script.isBlank() && existing?.packageFileName.isNullOrBlank()) {
                Toast.makeText(this, "Script cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val plugin = BrowserPlugin(
                id = existing?.id ?: System.currentTimeMillis().toString(),
                name = name,
                matchPattern = match,
                script = script,
                popupPath = existing?.popupPath,
                sidePanelPath = existing?.sidePanelPath,
                optionsPage = existing?.optionsPage,
                packageZipBase64 = existing?.packageZipBase64,
                packageFileName = existing?.packageFileName,
                enabled = enabledSwitch.isChecked,
                deepAccess = deepAccessSwitch.isChecked,
                allowToast = existing?.allowToast ?: true,
                allowClipboard = existing?.allowClipboard ?: false,
                allowShare = existing?.allowShare ?: false,
                allowOpenExternal = existing?.allowOpenExternal ?: false,
                allowReadUrl = existing?.allowReadUrl ?: true
            )
            upsertPlugin(plugin)
            Toast.makeText(this, "Plugin saved", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onDone?.invoke()
        }

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun pluginEditText(hintText: String, value: String, singleLine: Boolean): EditText {
        return EditText(this).apply {
            hint = hintText
            setText(value)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8A93A2.toInt())
            setBackgroundColor(0xFF151E2A.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isSingleLine = singleLine
        }
    }

    private fun pluginPackagesDir(): File {
        return File(filesDir, "plugin_packages").apply { mkdirs() }
    }

    private fun writePluginPackage(pluginId: String, zipBytes: ByteArray): String {
        val safeId = pluginId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val file = File(pluginPackagesDir(), "$safeId.zip")
        file.writeBytes(zipBytes)
        return file.name
    }

    private fun readPluginPackageBytes(plugin: BrowserPlugin): ByteArray? {
        plugin.packageFileName?.takeIf { it.isNotBlank() }?.let { fileName ->
            val file = File(pluginPackagesDir(), fileName)
            if (file.exists()) return file.readBytes()
        }
        return plugin.packageZipBase64?.takeIf { it.isNotBlank() }?.let { encoded ->
            try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: Exception) { null }
        }
    }

    private fun deletePluginPackage(plugin: BrowserPlugin) {
        plugin.packageFileName?.takeIf { it.isNotBlank() }?.let { fileName ->
            File(pluginPackagesDir(), fileName).delete()
        }
    }

    private fun clearPluginPackages() {
        pluginPackagesDir().listFiles()?.forEach { it.delete() }
    }

    private fun isUnresolvedExtensionName(name: String): Boolean {
        return name.matches(Regex("__MSG_[A-Za-z0-9_@.-]+__", RegexOption.IGNORE_CASE)) ||
                (name.startsWith("_") && name.endsWith("_") && name.length > 2)
    }

    private fun getPlugins(): MutableList<BrowserPlugin> {
        cachedPlugins?.let { return it.toMutableList() }
        val list = mutableListOf<BrowserPlugin>()
        var migratedPackages = false
        val arr = try {
            JSONArray(prefsManager.pluginsJson)
        } catch (e: Exception) {
            JSONArray()
        }
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", System.currentTimeMillis().toString())
                val legacyBase64 = obj.optString("packageZipBase64", "").ifBlank { null }
                var packageFileName = obj.optString("packageFileName", "").ifBlank { null }
                if (packageFileName.isNullOrBlank() && !legacyBase64.isNullOrBlank()) {
                    try {
                        packageFileName = writePluginPackage(id, Base64.decode(legacyBase64, Base64.DEFAULT))
                        migratedPackages = true
                    } catch (_: Exception) {
                    }
                }
                var name = obj.optString("name", "Plugin")
                if (isUnresolvedExtensionName(name) && !packageFileName.isNullOrBlank()) {
                    readPluginPackageBytes(
                        BrowserPlugin(
                            id = id,
                            name = name,
                            matchPattern = "*",
                            script = "",
                            packageFileName = packageFileName,
                            enabled = true,
                            deepAccess = false
                        )
                    )?.let { zipBytes ->
                        val resolvedName = ChromeExtensionInstaller.resolveExtensionName(zipBytes, name)
                        if (resolvedName != name && !isUnresolvedExtensionName(resolvedName)) {
                            name = resolvedName
                            migratedPackages = true
                        }
                    }
                }
                list.add(
                    BrowserPlugin(
                        id = id,
                        name = name,
                        matchPattern = obj.optString("matchPattern", "*"),
                        script = obj.optString("script", ""),
                        popupPath = obj.optString("popupPath", "").ifBlank { null },
                        sidePanelPath = obj.optString("sidePanelPath", "").ifBlank { null },
                        optionsPage = obj.optString("optionsPage", "").ifBlank { null },
                        packageZipBase64 = null,
                        packageFileName = packageFileName,
                        enabled = obj.optBoolean("enabled", true),
                        deepAccess = obj.optBoolean("deepAccess", false),
                        allowToast = obj.optBoolean("allowToast", true),
                        allowClipboard = obj.optBoolean("allowClipboard", false),
                        allowShare = obj.optBoolean("allowShare", false),
                        allowOpenExternal = obj.optBoolean("allowOpenExternal", false),
                        allowReadUrl = obj.optBoolean("allowReadUrl", true)
                    )
                )
            } catch (_: Exception) {
            }
        }
        if (migratedPackages) savePlugins(list)
        cachedPlugins = list.toMutableList()
        return list
    }

    private fun savePlugins(plugins: List<BrowserPlugin>) {
        val arr = JSONArray()
        plugins.forEach { plugin ->
            arr.put(
                JSONObject().apply {
                    put("id", plugin.id)
                    put("name", plugin.name)
                    put("matchPattern", plugin.matchPattern)
                    put("script", plugin.script)
                    put("popupPath", plugin.popupPath ?: "")
                    put("sidePanelPath", plugin.sidePanelPath ?: "")
                    put("optionsPage", plugin.optionsPage ?: "")
                    put("packageFileName", plugin.packageFileName ?: "")
                    put("enabled", plugin.enabled)
                    put("deepAccess", plugin.deepAccess)
                    put("allowToast", plugin.allowToast)
                    put("allowClipboard", plugin.allowClipboard)
                    put("allowShare", plugin.allowShare)
                    put("allowOpenExternal", plugin.allowOpenExternal)
                    put("allowReadUrl", plugin.allowReadUrl)
                }
            )
        }
        prefsManager.pluginsJson = arr.toString()
        cachedPlugins = plugins.toMutableList()
    }

    private fun upsertPlugin(plugin: BrowserPlugin) {
        val plugins = getPlugins()
        val existingIndex = plugins.indexOfFirst { it.id == plugin.id }
        if (existingIndex >= 0) {
            pluginBackgroundRuntimeReady.remove(plugin.id)
            pluginBackgroundRuntimes.remove(plugin.id)?.destroy()
            plugins[existingIndex] = plugin
        } else {
            plugins.add(plugin)
        }
        savePlugins(plugins)
    }

    private fun removePlugin(id: String) {
        val currentPlugins = getPlugins()
        currentPlugins.firstOrNull { it.id == id }?.let { deletePluginPackage(it) }
        pluginBackgroundRuntimeReady.remove(id)
        pluginBackgroundRuntimes.remove(id)?.destroy()
        val plugins = currentPlugins.filter { it.id != id }
        savePlugins(plugins)
    }

    private fun findPlugin(id: String): BrowserPlugin? = getPlugins().firstOrNull { it.id == id }

    private fun runPluginsForUrl(url: String) {
        val host = Uri.parse(url).host ?: ""
        getPlugins()
            .filter { it.enabled && doesPluginMatch(it.matchPattern, url, host) }
            .forEach { plugin -> runSinglePluginForUrl(plugin, url) }
    }

    private fun runSinglePluginForUrl(plugin: BrowserPlugin, url: String) {
        val host = Uri.parse(url).host ?: ""
        if (!plugin.enabled || !doesPluginMatch(plugin.matchPattern, url, host)) return

        ensureBackgroundRuntime(plugin)
        val wrappedScript = buildString {
            append("(function(){")
            append(buildChromeCompatLayer(plugin.id))
            append(buildPluginApiBootstrap(plugin))
            append("try{")
            append(plugin.script)
            append(";return JSON.stringify({ok:true});")
            append("}catch(e){return JSON.stringify({ok:false,error:String(e)});}")
            append("})();")
        }
        webView.evaluateJavascript(wrappedScript) { result ->
            val normalized = result?.trim('"') ?: ""
            if (normalized.contains("\"ok\":false")) {
                pluginLastError[plugin.id] = normalized
            } else {
                pluginLastError.remove(plugin.id)
            }
        }
    }

    private fun injectExtensionPageApiIfNeeded(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.host != "chrome-extension.local") return
        val extensionId = uri.pathSegments.firstOrNull().orEmpty()
        val plugin = findPlugin(extensionId) ?: return
        val bootstrap = buildString {
            append(buildChromeCompatLayer(plugin.id))
            append(buildPluginApiBootstrap(plugin))
            append("window.close=function(){if(window.ConsoleFlowHost)ConsoleFlowHost.closeExtensionSurfaceFor(")
            append(JSONObject.quote(plugin.id))
            append(");};")
        }
        webView.evaluateJavascript(bootstrap, null)
    }

    private fun getPluginMessagesJson(pluginId: String): String {
        pluginMessageCatalogs[pluginId]?.let { return it }
        val plugin = findPlugin(pluginId) ?: return "{}"
        val zipBytes = readPluginPackageBytes(plugin) ?: return "{}"
        val manifest = ChromeExtensionInstaller.readManifest(zipBytes)
        val localeCandidates = mutableListOf<String>()
        manifest?.optString("default_locale")?.takeIf { it.isNotBlank() }?.let { localeCandidates.add(it) }
        localeCandidates.add("en_US")
        localeCandidates.add("en")
        ChromeExtensionInstaller.listFilePaths(zipBytes)
            .filter { it.startsWith("_locales/") && it.endsWith("/messages.json") }
            .map { it.removePrefix("_locales/").substringBefore('/') }
            .forEach { if (!localeCandidates.contains(it)) localeCandidates.add(it) }

        val result = JSONObject()
        localeCandidates.forEach { locale ->
            val raw = ChromeExtensionInstaller.extractTextFileFromZip(zipBytes, "_locales/$locale/messages.json")
                ?: return@forEach
            val messages = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            messages.keys().forEach { key ->
                if (!result.has(key)) {
                    messages.optJSONObject(key)?.optString("message")?.takeIf { it.isNotBlank() }?.let { result.put(key, it) }
                }
            }
        }
        return result.toString().also { pluginMessageCatalogs[pluginId] = it }
    }

    private fun buildPluginApiBootstrap(plugin: BrowserPlugin): String {
        return buildString {
            append("window.ConsoleFlowPlugin={")
            append("name:${JSONObject.quote(plugin.name)},")
            append("match:${JSONObject.quote(plugin.matchPattern)},")
            append("deepAccess:${plugin.deepAccess}")
            append("};")
            append("window.ConsoleFlowApi={")
            append("toast:function(m){")
            append(
                if (plugin.allowToast) "if(window.ConsoleFlowHost)ConsoleFlowHost.toastFor(${JSONObject.quote(plugin.id)},String(m||''));"
                else ""
            )
            append("},")
            append("copyToClipboard:function(v){")
            append(
                if (plugin.deepAccess && plugin.allowClipboard) "if(window.ConsoleFlowHost)ConsoleFlowHost.copyToClipboardFor(${JSONObject.quote(plugin.id)},String(v||''));"
                else ""
            )
            append("},")
            append("shareText:function(v){")
            append(
                if (plugin.deepAccess && plugin.allowShare) "if(window.ConsoleFlowHost)ConsoleFlowHost.shareTextFor(${JSONObject.quote(plugin.id)},String(v||''));"
                else ""
            )
            append("},")
            append("openExternal:function(v){")
            append(
                if (plugin.deepAccess && plugin.allowOpenExternal) "if(window.ConsoleFlowHost)ConsoleFlowHost.openExternalFor(${JSONObject.quote(plugin.id)},String(v||''));"
                else ""
            )
            append("},")
            append("currentUrl:function(){")
            append(
                if (plugin.allowReadUrl) "if(window.ConsoleFlowHost)return ConsoleFlowHost.currentUrlFor(${JSONObject.quote(plugin.id)});return '';"
                else "return '';"
            )
            append("}")
            append("};")
        }
    }

    private fun buildChromeCompatLayer(extensionId: String): String {
        val plugin = findPlugin(extensionId)
        val defaultPopupPath = plugin?.popupPath.orEmpty()
        val defaultSidePanelPath = plugin?.sidePanelPath.orEmpty()
        val messagesJson = getPluginMessagesJson(extensionId)
        return """
(function(){
  if(!window.__cfExtBus){window.__cfExtBus={listeners:{}};}
  function addListener(channel,fn){
    if(!window.__cfExtBus.listeners[channel])window.__cfExtBus.listeners[channel]=[];
    window.__cfExtBus.listeners[channel].push(fn);
  }
  function emit(channel,payload,sender,sendResponse){
    var list=(window.__cfExtBus.listeners[channel]||[]).slice();
    var handled=false;
    list.forEach(function(fn){
      try{
        var ret=fn(payload,sender||{id:window.chrome&&window.chrome.runtime&&window.chrome.runtime.id,url:location.href},function(r){handled=true;if(sendResponse)sendResponse(r);});
        if(ret&&typeof ret.then==='function'){handled=true;ret.then(function(r){if(sendResponse)sendResponse(r);}).catch(function(e){if(sendResponse)sendResponse({error:String(e)});});}
        else if(ret!==undefined&&ret!==true){handled=true;if(sendResponse)sendResponse(ret);}
        else if(ret===true){handled=true;}
      }catch(e){handled=true;if(sendResponse)sendResponse({error:String(e)});}
    });
    return handled;
  }

  if(!window.__cfRuntimeMessageCallbacks){window.__cfRuntimeMessageCallbacks={};}
  window.__cfResolveRuntimeMessage=function(callbackId,responseJson){
    var cb=window.__cfRuntimeMessageCallbacks&&window.__cfRuntimeMessageCallbacks[callbackId];
    if(!cb)return;
    delete window.__cfRuntimeMessageCallbacks[callbackId];
    var r=null;try{r=JSON.parse(responseJson||'null');}catch(e){}
    try{cb(r);}catch(e){}
  };
  window.__cfDispatchRuntimeMessage=function(msgJson,senderJson,callbackId){
    var msg=null,sender={id:window.chrome&&window.chrome.runtime&&window.chrome.runtime.id,url:location.href};
    try{msg=JSON.parse(msgJson||'null');}catch(e){}
    try{sender=JSON.parse(senderJson||'{}')||sender;}catch(e){}
    var responded=false;
    function sendResponse(r){
      if(responded)return;responded=true;
      try{if(window.ConsoleFlowHost)ConsoleFlowHost.completeRuntimeMessageFor(window.chrome.runtime.id,callbackId||'',JSON.stringify(r===undefined?null:r));}catch(e){}
    }
    var handled=emit('runtime:onMessage',msg,sender,sendResponse);
    if(!handled){sendResponse(null);}
    return true;
  };

  if(!window.chrome){window.chrome={};}
  if(!window.chrome.runtime){window.chrome.runtime={};}
  if(!window.chrome.storage){window.chrome.storage={};}
  if(!window.chrome.tabs){window.chrome.tabs={};}
  if(!window.chrome.scripting){window.chrome.scripting={};}
  if(!window.chrome.action){window.chrome.action={};}
  if(!window.chrome.browserAction){window.chrome.browserAction={};}
  if(!window.chrome.sidePanel){window.chrome.sidePanel={};}
  if(!window.chrome.windows){window.chrome.windows={};}
  if(!window.chrome.i18n){window.chrome.i18n={};}
  if(!window.chrome.alarms){window.chrome.alarms={};}
  if(!window.chrome.cookies){window.chrome.cookies={};}
  if(!window.chrome.permissions){window.chrome.permissions={};}
  if(!window.chrome.webNavigation){window.chrome.webNavigation={};}
  if(!window.chrome.webRequest){window.chrome.webRequest={};}
  if(!window.chrome.declarativeNetRequest){window.chrome.declarativeNetRequest={};}
  if(!window.chrome.notifications){window.chrome.notifications={};}
  if(!window.chrome.commands){window.chrome.commands={};}
  if(!window.chrome.identity){window.chrome.identity={};}
  if(!window.chrome.downloads){window.chrome.downloads={};}

  var storeKey='__cf_store__' + ${JSONObject.quote(extensionId)};
  window.chrome.runtime.id=${JSONObject.quote(extensionId)};
  window.chrome.runtime.getURL=function(path){
    return 'https://chrome-extension.local/'+window.chrome.runtime.id+'/'+String(path||'').replace(/^\/+/,'');
  };
  
  // Runtime
  window.chrome.runtime.lastError=null;
  window.chrome.runtime.getManifest=function(){return {manifest_version:3,name:'ConsoleFlowExt',version:'1.0',id:window.chrome.runtime.id};};
  window.chrome.runtime.onMessage={addListener:function(fn){addListener('runtime:onMessage',fn);}};
  window.chrome.runtime.onInstalled={addListener:function(fn){addListener('runtime:onInstalled',fn);}};
  window.chrome.runtime.onStartup={addListener:function(fn){addListener('runtime:onStartup',fn);}};
  window.chrome.runtime.onConnect={addListener:function(fn){addListener('runtime:onConnect',fn);}};
  window.chrome.runtime.sendMessage=function(extIdOrMsg,msgOrOpts,optsOrCb){
    var msg=extIdOrMsg,cb=null;
    if(typeof extIdOrMsg==='string'){msg=msgOrOpts;cb=(typeof optsOrCb==='function')?optsOrCb:null;}
    else{cb=(typeof msgOrOpts==='function')?msgOrOpts:((typeof optsOrCb==='function')?optsOrCb:null);}
    var cid='rt_'+Date.now()+'_'+Math.random().toString(36).slice(2);
    var p=null;
    if(cb){window.__cfRuntimeMessageCallbacks[cid]=cb;}
    else{p=new Promise(function(res){window.__cfRuntimeMessageCallbacks[cid]=res;});}
    try{
      if(window.ConsoleFlowHost){window.ConsoleFlowHost.sendRuntimeMessageFor(window.chrome.runtime.id,JSON.stringify(msg===undefined?null:msg),location.href,cid);}
      else{window.__cfDispatchRuntimeMessage(JSON.stringify(msg===undefined?null:msg),JSON.stringify({id:window.chrome.runtime.id,url:location.href}),cid);}
    }catch(e){window.__cfResolveRuntimeMessage(cid,JSON.stringify({error:String(e)}));}
    return p;
  };
  window.chrome.runtime.connect=function(extId,connectInfo){
    var portId='port_'+Date.now()+'_'+Math.random().toString(36).slice(2);
    var port={
      name:connectInfo?connectInfo.name:'',
      postMessage:function(msg){try{if(window.ConsoleFlowHost)window.ConsoleFlowHost.postMessageForPort(window.chrome.runtime.id,portId,JSON.stringify(msg));}catch(e){}},
      disconnect:function(){try{if(window.ConsoleFlowHost)window.ConsoleFlowHost.disconnectPort(window.chrome.runtime.id,portId);}catch(e){}},
      onMessage:{addListener:function(fn){addListener('port:onMessage:'+portId,fn);}},
      onDisconnect:{addListener:function(fn){addListener('port:onDisconnect:'+portId,fn);}}
    };
    try{if(window.ConsoleFlowHost)window.ConsoleFlowHost.connectPort(window.chrome.runtime.id,portId,connectInfo?connectInfo.name:'');}catch(e){}
    return port;
  };
  window.chrome.runtime.getPlatformInfo=function(cb){var r={os:'android',arch:'arm',nacl_arch:'arm',platform:'linux'};if(cb)cb(r);return Promise.resolve(r);};
  window.chrome.runtime.reload=function(){};
  window.chrome.runtime.setUninstallURL=function(){};
  window.chrome.runtime.openOptionsPage=function(cb){if(cb)cb();return Promise.resolve();};
  window.chrome.runtime.getBackgroundPage=function(cb){if(cb)cb(null);return Promise.resolve(null);};

  // Storage
  var storageImpl={
    get:function(keys,cb){
      try{var raw=localStorage.getItem(storeKey)||'{}';var data=JSON.parse(raw);var out={};
        if(Array.isArray(keys)){keys.forEach(function(k){out[k]=data[k];});}
        else if(typeof keys==='string'){out[keys]=data[keys];}
        else if(keys&&typeof keys==='object'){Object.keys(keys).forEach(function(k){out[k]=data[k]===undefined?keys[k]:data[k];});}
        else{out=data;}
        if(cb)cb(out); return Promise.resolve(out);
      }catch(e){if(cb)cb({}); return Promise.resolve({});}
    },
    set:function(items,cb){
      try{var raw=localStorage.getItem(storeKey)||'{}';var data=JSON.parse(raw);
        Object.keys(items||{}).forEach(function(k){data[k]=items[k];});
        localStorage.setItem(storeKey,JSON.stringify(data));
      }catch(e){}if(cb)cb(); return Promise.resolve();
    },
    remove:function(keys,cb){
      try{var raw=localStorage.getItem(storeKey)||'{}';var data=JSON.parse(raw);
        var arr=Array.isArray(keys)?keys:[keys];arr.forEach(function(k){delete data[k];});
        localStorage.setItem(storeKey,JSON.stringify(data));
      }catch(e){}if(cb)cb(); return Promise.resolve();
    },
    clear:function(cb){try{localStorage.removeItem(storeKey);}catch(e){}if(cb)cb(); return Promise.resolve();}
  };
  window.chrome.storage.local=storageImpl;
  window.chrome.storage.sync=storageImpl;
  window.chrome.storage.session=storageImpl;
  window.chrome.storage.onChanged={addListener:function(fn){addListener('storage:onChanged',fn);}};

  // Tabs
  window.chrome.tabs.query=function(q,cb){
    var fb=[{id:1,active:true,currentWindow:true,url:location.href,title:document.title||''}];
    try{if(window.ConsoleFlowHost){fb=JSON.parse(window.ConsoleFlowHost.activeTabsJsonFor(window.chrome.runtime.id,JSON.stringify(q||{}))||'[]');}}catch(e){}
    if(cb)cb(fb);return Promise.resolve(fb);
  };
  window.chrome.tabs.sendMessage=function(tabId,msg,optsOrCb){
    var cb=(typeof optsOrCb==='function')?optsOrCb:null;
    var cid='tab_'+Date.now()+'_'+Math.random().toString(36).slice(2);
    var p=null;
    if(cb){window.__cfRuntimeMessageCallbacks[cid]=cb;}else{p=new Promise(function(res){window.__cfRuntimeMessageCallbacks[cid]=res;});}
    try{if(window.ConsoleFlowHost){window.ConsoleFlowHost.sendTabMessageFor(window.chrome.runtime.id,Number(tabId)||1,JSON.stringify(msg===undefined?null:msg),location.href,cid);}}catch(e){window.__cfResolveRuntimeMessage(cid,JSON.stringify({error:String(e)}));}
    return p;
  };
  window.chrome.tabs.create=function(props,cb){
    var u=(props&&props.url)||'about:blank';
    try{if(window.ConsoleFlowHost){window.ConsoleFlowHost.openTabFor(window.chrome.runtime.id,u,!(props&&props.active===false));}}catch(e){}
    var t={id:Date.now(),active:!(props&&props.active===false),url:u};
    if(cb)cb(t); return Promise.resolve(t);
  };
  window.chrome.tabs.update=function(tabId,props,cb){
    try{if(window.ConsoleFlowHost)window.ConsoleFlowHost.updateTabFor(window.chrome.runtime.id,Number(tabId)||1,JSON.stringify(props));}catch(e){}
    var t={id:tabId||1,...props}; if(cb)cb(t); return Promise.resolve(t);
  };
  window.chrome.tabs.remove=function(tabId,cb){
    try{if(window.ConsoleFlowHost)window.ConsoleFlowHost.removeTabFor(window.chrome.runtime.id,Number(tabId)||1);}catch(e){}
    if(cb)cb(); return Promise.resolve();
  };
  window.chrome.tabs.get=function(tabId,cb){var t={id:tabId,active:true,url:location.href};if(cb)cb(t);return Promise.resolve(t);};
  window.chrome.tabs.getCurrent=function(cb){var t={id:1,active:true,url:location.href};if(cb)cb(t);return Promise.resolve(t);};
  window.chrome.tabs.reload=function(tabId,opt,cb){if(cb)cb();return Promise.resolve();};
  window.chrome.tabs.getSelected=function(wid,cb){var t={id:1,active:true,url:location.href};if(cb)cb(t);return Promise.resolve(t);};
  window.chrome.tabs.onActivated={addListener:function(fn){addListener('tabs:onActivated',fn);}};
  window.chrome.tabs.onUpdated={addListener:function(fn){addListener('tabs:onUpdated',fn);}};
  window.chrome.tabs.onRemoved={addListener:function(fn){addListener('tabs:onRemoved',fn);}};

  // Scripting
  window.chrome.scripting.executeScript=function(inj,cb){
    inj=inj||{};var cid='script_'+Date.now()+'_'+Math.random().toString(36).slice(2);
    var p=null;if(cb){window.__cfRuntimeMessageCallbacks[cid]=cb;}else{p=new Promise(function(res){window.__cfRuntimeMessageCallbacks[cid]=res;});}
    var code='';
    try{var fn=inj.func||inj.function;if(fn){code='var __cfResult=('+fn.toString()+').apply(null,'+JSON.stringify(inj.args||[])+');';}else if(inj.code){code=String(inj.code);}
      if(window.ConsoleFlowHost){window.ConsoleFlowHost.executeScriptFor(window.chrome.runtime.id,code,JSON.stringify(inj.files||[]),cid);}
    }catch(e){window.__cfResolveRuntimeMessage(cid,JSON.stringify({error:String(e)}));}
    return p;
  };
  window.chrome.scripting.insertCSS=function(inj,cb){
    inj=inj||{};var cid='css_'+Date.now()+'_'+Math.random().toString(36).slice(2);
    var p=null;if(cb){window.__cfRuntimeMessageCallbacks[cid]=cb;}else{p=new Promise(function(res){window.__cfRuntimeMessageCallbacks[cid]=res;});}
    try{if(window.ConsoleFlowHost){window.ConsoleFlowHost.insertCssFor(window.chrome.runtime.id,String(inj.css||''),JSON.stringify(inj.files||[]),cid);}}catch(e){window.__cfResolveRuntimeMessage(cid,JSON.stringify({error:String(e)}));}
    return p;
  };
  window.chrome.scripting.removeCSS=function(inj,cb){if(cb)cb();return Promise.resolve();};
  window.chrome.scripting.registerContentScripts=function(s,cb){if(cb)cb();return Promise.resolve();};

  // Backwards compat for Scripting in Tabs
  window.chrome.tabs.executeScript=function(tid,d,cb){if(typeof tid==='object'){cb=d;d=tid;}d=d||{};return window.chrome.scripting.executeScript({target:{tabId:tid||1},files:d.file?[d.file]:(d.files||[]),code:d.code},cb);};
  window.chrome.tabs.insertCSS=function(tid,d,cb){if(typeof tid==='object'){cb=d;d=tid;}d=d||{};return window.chrome.scripting.insertCSS({target:{tabId:tid||1},files:d.file?[d.file]:(d.files||[]),css:d.code||d.css||''},cb);};

  // Windows
  window.chrome.windows.create=function(d,cb){
    var u=(d&&d.url)||'';if(Array.isArray(u)){u=u[0]||'';}
    try{if(window.ConsoleFlowHost){window.ConsoleFlowHost.openWindowFor(window.chrome.runtime.id,u,String((d&&d.type)||'popup'),Number((d&&d.width)||0),Number((d&&d.height)||0));}}catch(e){}
    var w={id:Date.now(),focused:true,type:(d&&d.type)||'popup',tabs:[{id:Date.now()+1,url:u}]};
    if(cb)cb(w); return Promise.resolve(w);
  };
  window.chrome.windows.getCurrent=function(_,cb){var c=typeof _==='function'?_:cb; var w={id:1,focused:true,type:'normal'}; if(c)c(w); return Promise.resolve(w);};
  window.chrome.windows.get=function(wid,_,cb){var c=typeof _==='function'?_:cb; var w={id:wid,focused:true,type:'normal'}; if(c)c(w); return Promise.resolve(w);};
  window.chrome.windows.getAll=function(_,cb){var c=typeof _==='function'?_:cb; var w=[{id:1,focused:true,type:'normal'}]; if(c)c(w); return Promise.resolve(w);};
  window.chrome.windows.update=function(wid,props,cb){var w={id:wid,...props}; if(cb)cb(w); return Promise.resolve(w);};
  window.chrome.windows.remove=function(wid,cb){try{if(window.ConsoleFlowHost)window.ConsoleFlowHost.closeExtensionSurfaceFor(window.chrome.runtime.id);}catch(e){}if(cb)cb(); return Promise.resolve();};
  window.chrome.windows.getLastFocused=function(o,cb){var c=typeof o==='function'?o:cb; var w={id:1,focused:true,type:'normal'}; if(c)c(w); return Promise.resolve(w);};
  window.chrome.windows.onFocusChanged={addListener:function(fn){addListener('windows:onFocusChanged',fn);}};

  // i18n
  var __cfMessages=$messagesJson;
  window.chrome.i18n.getMessage=function(name,substitutions){
    if(name==='@@extension_id')return window.chrome.runtime.id;
    if(name==='@@ui_locale')return 'en_US';
    var msg=__cfMessages&&__cfMessages[name];
    if(!msg)return '';
    var subs=Array.isArray(substitutions)?substitutions:(substitutions===undefined?[]:[substitutions]);
    subs.forEach(function(v,i){msg=String(msg).replace(new RegExp('\\\$'+(i+1),'g'),String(v));});
    return msg;
  };
  window.chrome.i18n.getUILanguage=function(){return 'en-US';};
  window.chrome.i18n.detectLanguage=function(t,cb){var r={isReliable:true,languages:[{language:'en',percentage:100}]};if(cb)cb(r);return Promise.resolve(r);};

  // Action / BrowserAction
  var popupPath=window.__cfActionPopup||${JSONObject.quote(defaultPopupPath)};
  function setupActionApi(t){
    t.onClicked={addListener:function(fn){addListener('action:onClicked',fn);}};
    t.setBadgeText=function(_,cb){if(cb)cb();};
    t.setBadgeBackgroundColor=function(_,cb){if(cb)cb();};
    t.setTitle=function(_,cb){if(cb)cb();};
    t.setIcon=function(_,cb){if(cb)cb();};
    t.setPopup=function(d,cb){popupPath=(d&&d.popup)||'';window.__cfActionPopup=popupPath;if(cb)cb();};
    t.getPopup=function(_,cb){if(cb)cb(popupPath||'');};
    t.openPopup=function(o,cb){try{if(window.ConsoleFlowHost)window.ConsoleFlowHost.openPopupPathFor(window.chrome.runtime.id,popupPath||'');}catch(e){}if(cb)cb();};
    t.enable=function(tid,cb){if(cb)cb();};
    t.disable=function(tid,cb){if(cb)cb();};
  }
  setupActionApi(window.chrome.action);
  setupActionApi(window.chrome.browserAction);

  // SidePanel
  var sidePanelPath=window.__cfSidePanelPath||${JSONObject.quote(defaultSidePanelPath)};
  window.chrome.sidePanel.setOptions=function(o,cb){if(o&&o.path){sidePanelPath=o.path;window.__cfSidePanelPath=sidePanelPath;}if(cb)cb();};
  window.chrome.sidePanel.getOptions=function(o,cb){if(cb)cb({enabled:!!sidePanelPath,path:sidePanelPath||''});};
  window.chrome.sidePanel.open=function(o,cb){try{if(window.ConsoleFlowHost)window.ConsoleFlowHost.openSidePanelFor(window.chrome.runtime.id,sidePanelPath||'');}catch(e){}if(cb)cb();};
  window.chrome.sidePanel.setPanelBehavior=function(o,cb){window.__cfOpenPanelOnActionClick=!!(o&&o.openPanelOnActionClick);if(cb)cb();};

  // ContextMenus
  window.chrome.contextMenus.create=function(info,cb){if(cb)cb(info&&info.id);return info&&info.id;};
  window.chrome.contextMenus.update=function(id,info,cb){if(cb)cb();};
  window.chrome.contextMenus.remove=function(id,cb){if(cb)cb();};
  window.chrome.contextMenus.removeAll=function(cb){if(cb)cb();};
  window.chrome.contextMenus.onClicked={addListener:function(fn){addListener('contextMenus:onClicked',fn);}};

  // Alarms
  window.chrome.alarms.create=function(){};
  window.chrome.alarms.get=function(n,cb){if(cb)cb(null);};
  window.chrome.alarms.getAll=function(cb){if(cb)cb([]);};
  window.chrome.alarms.clear=function(n,cb){if(cb)cb(true);};
  window.chrome.alarms.clearAll=function(cb){if(cb)cb(true);};
  window.chrome.alarms.onAlarm={addListener:function(fn){addListener('alarms:onAlarm',fn);}};

  // Cookies
  window.chrome.cookies.get=function(d,cb){if(cb)cb(null);};
  window.chrome.cookies.getAll=function(d,cb){if(cb)cb([]);};
  window.chrome.cookies.set=function(d,cb){if(cb)cb(d);};
  window.chrome.cookies.remove=function(d,cb){if(cb)cb({name:d.name,url:d.url});};
  window.chrome.cookies.onChanged={addListener:function(fn){addListener('cookies:onChanged',fn);}};

  // Permissions
  window.chrome.permissions.contains=function(o,cb){if(cb)cb(true); return Promise.resolve(true);};
  window.chrome.permissions.request=function(o,cb){if(cb)cb(true); return Promise.resolve(true);};
  window.chrome.permissions.remove=function(o,cb){if(cb)cb(true); return Promise.resolve(true);};
  window.chrome.permissions.getAll=function(cb){var r={origins:[],permissions:[]}; if(cb)cb(r); return Promise.resolve(r);};

  // WebNavigation
  window.chrome.webNavigation.onCompleted={addListener:function(fn){addListener('webNavigation:onCompleted',fn);}};
  window.chrome.webNavigation.onCommitted={addListener:function(fn){addListener('webNavigation:onCommitted',fn);}};
  window.chrome.webNavigation.onDOMContentLoaded={addListener:function(fn){addListener('webNavigation:onDOMContentLoaded',fn);}};
  window.chrome.webNavigation.onBeforeNavigate={addListener:function(fn){addListener('webNavigation:onBeforeNavigate',fn);}};
  window.chrome.webNavigation.onHistoryStateUpdated={addListener:function(fn){addListener('webNavigation:onHistoryStateUpdated',fn);}};

  // WebRequest
  window.chrome.webRequest.onBeforeRequest={addListener:function(fn){addListener('webRequest:onBeforeRequest',fn);}};
  window.chrome.webRequest.onBeforeSendHeaders={addListener:function(fn){addListener('webRequest:onBeforeSendHeaders',fn);}};
  window.chrome.webRequest.onHeadersReceived={addListener:function(fn){addListener('webRequest:onHeadersReceived',fn);}};
  window.chrome.webRequest.onCompleted={addListener:function(fn){addListener('webRequest:onCompleted',fn);}};

  // DeclarativeNetRequest
  window.chrome.declarativeNetRequest.updateDynamicRules=function(o,cb){if(cb)cb();return Promise.resolve();};
  window.chrome.declarativeNetRequest.getDynamicRules=function(cb){if(cb)cb([]);return Promise.resolve([]);};
  window.chrome.declarativeNetRequest.updateSessionRules=function(o,cb){if(cb)cb();return Promise.resolve();};
  window.chrome.declarativeNetRequest.getSessionRules=function(cb){if(cb)cb([]);return Promise.resolve([]);};

  // Notifications
  window.chrome.notifications.create=function(id,o,cb){if(cb)cb(id);return Promise.resolve(id);};
  window.chrome.notifications.update=function(id,o,cb){if(cb)cb(true);return Promise.resolve(true);};
  window.chrome.notifications.clear=function(id,cb){if(cb)cb(true);return Promise.resolve(true);};
  window.chrome.notifications.getAll=function(cb){if(cb)cb({});return Promise.resolve({});};
  window.chrome.notifications.onClicked={addListener:function(fn){addListener('notifications:onClicked',fn);}};
  window.chrome.notifications.onClosed={addListener:function(fn){addListener('notifications:onClosed',fn);}};

  // Commands
  window.chrome.commands.onCommand={addListener:function(fn){addListener('commands:onCommand',fn);}};
  window.chrome.commands.getAll=function(cb){if(cb)cb([]);return Promise.resolve([]);};

  // Identity
  window.chrome.identity.getAuthToken=function(o,cb){if(cb)cb('');return Promise.resolve('');};
  window.chrome.identity.launchWebAuthFlow=function(o,cb){if(cb)cb('');return Promise.resolve('');};

  // Downloads
  window.chrome.downloads.download=function(o,cb){if(cb)cb(1);return Promise.resolve(1);};
  window.chrome.downloads.onChanged={addListener:function(fn){addListener('downloads:onChanged',fn);}};

  // Clipboard / ExecCommand
  if(!document.execCommand){
    document.execCommand=function(command,showUI,value){
      if(command==='copy'||command==='cut'){
        try{if(window.ConsoleFlowHost)window.ConsoleFlowHost.execCommandFor(window.chrome.runtime.id,command,value||'');return true;}catch(e){}
      }
      return false;
    };
  }
  if(!navigator.clipboard){
    navigator.clipboard={
      readText:function(){return Promise.resolve('');},
      writeText:function(t){try{if(window.ConsoleFlowHost)window.ConsoleFlowHost.writeClipboardTextFor(window.chrome.runtime.id,t);}catch(e){}return Promise.resolve();}
    };
  }

  if(!window.browser){window.browser=window.chrome;}

  if(!window.__cfInstalledFired){
    window.__cfInstalledFired=true;
    setTimeout(function(){emit('runtime:onInstalled',{reason:'install'});},0);
  }
})();
""".trimIndent()
    }

    private fun doesPluginMatch(pattern: String, url: String, host: String): Boolean {
        if (pattern == "*") return true
        val normalized = pattern.trim().lowercase()
        return host.lowercase().contains(normalized) || url.lowercase().contains(normalized)
    }

    private fun guessMimeType(path: String): String {
        return when {
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".html") -> "text/html"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".webp") -> "image/webp"
            else -> "text/plain"
        }
    }

    private fun clearData() {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        webView.clearCache(true)
        webView.clearHistory()
        prefsManager.clearHistory()
        Toast.makeText(this, "Data Cleared", Toast.LENGTH_SHORT).show()
    }

    // ── User Agent & Desktop Mode ──────────────────────────────────────────────
    private fun updateUserAgent() {
        val settings = webView.settings
        if (prefsManager.desktopMode) {
            // Real Chrome desktop UA — recognized by all major sites
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        } else {
            settings.userAgentString = WebSettings.getDefaultUserAgent(this)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
        }
    }

    // ── Search Engine Icon (local drawables — no network needed) ──────────────
    private fun updateSearchEngineIcon() {
        val engine = prefsManager.searchEngine
        val iconRes = when {
            engine.contains("google")     -> R.drawable.ic_engine_google
            engine.contains("duckduckgo") -> R.drawable.ic_engine_duckduckgo
            engine.contains("bing")       -> R.drawable.ic_engine_bing
            engine.contains("brave")      -> R.drawable.ic_engine_brave
            else                          -> R.drawable.ic_engine_google
        }
        imgSearchEngine.setImageResource(iconRes)
        imgSearchEngine.colorFilter = null
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private fun updateBookmarkIcon(url: String) {
        btnBookmark.alpha = if (prefsManager.isBookmarked(url)) 1.0f else 0.45f
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(textUrl.windowToken, 0)
    }

    private fun hideCustomView() {
        customViewCallback?.onCustomViewHidden()
        fullscreenContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        customView = null
    }

    inner class SearchBridge {
        @JavascriptInterface
        fun navigate(input: String) {
            runOnUiThread {
                val finalUrl = when {
                    input.startsWith("http://") || input.startsWith("https://") -> input
                    Patterns.WEB_URL.matcher(input).matches() -> "https://$input"
                    else -> prefsManager.searchEngine + input
                }
                webView.loadUrl(finalUrl)
            }
        }

        @JavascriptInterface
        fun installFromStore(storeUrl: String) {
            runOnUiThread {
                installChromeStoreExtension(storeUrl)
            }
        }
    }

    private fun dispatchRuntimeMessageToWebView(target: WebView, plugin: BrowserPlugin, messageJson: String, senderUrl: String, callbackId: String) {
        val senderJson = JSONObject().apply {
            put("id", plugin.id)
            put("url", senderUrl)
            put("tab", JSONObject().apply {
                put("id", 1)
                put("active", true)
                put("currentWindow", true)
                put("url", if (plugin.allowReadUrl) webView.url.orEmpty() else "")
                put("title", webView.title.orEmpty())
            })
        }.toString()
        val script = "window.__cfDispatchRuntimeMessage&&window.__cfDispatchRuntimeMessage(" +
            JSONObject.quote(messageJson) + "," +
            JSONObject.quote(senderJson) + "," +
            JSONObject.quote(callbackId) +
            ");"
        target.evaluateJavascript(script, null)
    }

    private fun routeRuntimeMessage(plugin: BrowserPlugin, source: WebView?, messageJson: String, senderUrl: String, callbackId: String, attempt: Int = 0) {
        val background = pluginBackgroundRuntimes[plugin.id]
        when {
            background != null && background !== source && pluginBackgroundRuntimeReady.contains(plugin.id) -> {
                dispatchRuntimeMessageToWebView(background, plugin, messageJson, senderUrl, callbackId)
            }
            background != null && background !== source && attempt < 30 -> {
                background.postDelayed({
                    routeRuntimeMessage(plugin, source, messageJson, senderUrl, callbackId, attempt + 1)
                }, 100L)
            }
            webView !== source -> {
                dispatchRuntimeMessageToWebView(webView, plugin, messageJson, senderUrl, callbackId)
            }
            else -> {
                completeRuntimeMessage(callbackId, "null")
            }
        }
    }

    private fun completeRuntimeMessage(callbackId: String, responseJson: String) {
        if (callbackId.isBlank()) return
        val target = pendingRuntimeMessageTargets.remove(callbackId) ?: return
        val script = "window.__cfResolveRuntimeMessage&&window.__cfResolveRuntimeMessage(" +
            JSONObject.quote(callbackId) + "," +
            JSONObject.quote(responseJson.ifBlank { "null" }) +
            ");"
        target.evaluateJavascript(script, null)
    }

    inner class PluginBridge {
        var sourceWebView: WebView? = null

        @JavascriptInterface
        fun toastFor(pluginId: String, message: String) {
            val plugin = findPlugin(pluginId) ?: return
            if (!plugin.allowToast) return
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun copyToClipboardFor(pluginId: String, value: String) {
            val plugin = findPlugin(pluginId) ?: return
            if (!plugin.deepAccess || !plugin.allowClipboard) return
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ConsoleFlow Plugin", value))
        }

        @JavascriptInterface
        fun shareTextFor(pluginId: String, value: String) {
            val plugin = findPlugin(pluginId) ?: return
            if (!plugin.deepAccess || !plugin.allowShare) return
            runOnUiThread {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, value)
                }
                startActivity(Intent.createChooser(share, "Share from Plugin"))
            }
        }

        @JavascriptInterface
        fun openExternalFor(pluginId: String, url: String) {
            val plugin = findPlugin(pluginId) ?: return
            if (!plugin.deepAccess || !plugin.allowOpenExternal) return
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Unable to open link", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun currentUrlFor(pluginId: String): String {
            val plugin = findPlugin(pluginId) ?: return ""
            if (!plugin.allowReadUrl) return ""
            return webView.url ?: ""
        }

        @JavascriptInterface
        fun activeTabsJsonFor(pluginId: String, queryInfoJson: String): String {
            val plugin = findPlugin(pluginId) ?: return "[]"
            val tab = JSONObject().apply {
                put("id", 1)
                put("active", true)
                put("currentWindow", true)
                if (plugin.allowReadUrl) put("url", webView.url.orEmpty())
                put("title", webView.title.orEmpty())
            }
            return JSONArray().put(tab).toString()
        }

        @JavascriptInterface
        fun sendRuntimeMessageFor(pluginId: String, messageJson: String, senderUrl: String, callbackId: String) {
            val plugin = findPlugin(pluginId) ?: return runOnUiThread { completeRuntimeMessage(callbackId, "null") }
            sourceWebView?.let { pendingRuntimeMessageTargets[callbackId] = it }
            runOnUiThread {
                ensureBackgroundRuntime(plugin)
                routeRuntimeMessage(plugin, sourceWebView, messageJson, senderUrl, callbackId)
            }
        }

        @JavascriptInterface
        fun sendTabMessageFor(pluginId: String, tabId: Int, messageJson: String, senderUrl: String, callbackId: String) {
            val plugin = findPlugin(pluginId) ?: return runOnUiThread { completeRuntimeMessage(callbackId, "null") }
            sourceWebView?.let { pendingRuntimeMessageTargets[callbackId] = it }
            runOnUiThread {
                dispatchRuntimeMessageToWebView(webView, plugin, messageJson, senderUrl, callbackId)
            }
        }

        @JavascriptInterface
        fun completeRuntimeMessageFor(pluginId: String, callbackId: String, responseJson: String) {
            findPlugin(pluginId) ?: return
            runOnUiThread { completeRuntimeMessage(callbackId, responseJson) }
        }

        @JavascriptInterface
        fun executeScriptFor(pluginId: String, code: String, filesJson: String, callbackId: String) {
            val plugin = findPlugin(pluginId) ?: return runOnUiThread { completeRuntimeMessage(callbackId, "null") }
            sourceWebView?.let { pendingRuntimeMessageTargets[callbackId] = it }
            runOnUiThread {
                val script = buildString {
                    append("(function(){try{var __cfResult=null;")
                    val zipBytes = readPluginPackageBytes(plugin)
                    val files = runCatching { JSONArray(filesJson) }.getOrNull() ?: JSONArray()
                    for (i in 0 until files.length()) {
                        val filePath = normalizeExtensionPath(plugin, files.optString(i))
                        val fileSource = zipBytes?.let { ChromeExtensionInstaller.extractTextFileFromZip(it, filePath) }.orEmpty()
                        if (fileSource.isNotBlank()) {
                            append("\n/* ").append(filePath.replace("*/", "")).append(" */\n")
                            append(fileSource).append("\n")
                        }
                    }
                    if (code.isNotBlank()) append(code).append("\n")
                    append("return JSON.stringify({result:__cfResult});")
                    append("}catch(e){return JSON.stringify({error:String(e)});}})();")
                }
                webView.evaluateJavascript(script) { result ->
                    val payloadText = runCatching { org.json.JSONTokener(result ?: "null").nextValue() as? String }.getOrNull() ?: "{}"
                    val payload = runCatching { JSONObject(payloadText) }.getOrNull() ?: JSONObject()
                    val response = if (payload.has("error")) {
                        JSONObject().put("error", payload.optString("error")).toString()
                    } else {
                        JSONArray().put(JSONObject().put("result", payload.opt("result"))).toString()
                    }
                    completeRuntimeMessage(callbackId, response)
                }
            }
        }

        @JavascriptInterface
        fun insertCssFor(pluginId: String, css: String, filesJson: String, callbackId: String) {
            val plugin = findPlugin(pluginId) ?: return runOnUiThread { completeRuntimeMessage(callbackId, "null") }
            sourceWebView?.let { pendingRuntimeMessageTargets[callbackId] = it }
            runOnUiThread {
                val cssBuilder = StringBuilder(css)
                val zipBytes = readPluginPackageBytes(plugin)
                val files = runCatching { JSONArray(filesJson) }.getOrNull() ?: JSONArray()
                for (i in 0 until files.length()) {
                    val filePath = normalizeExtensionPath(plugin, files.optString(i))
                    val fileSource = zipBytes?.let { ChromeExtensionInstaller.extractTextFileFromZip(it, filePath) }.orEmpty()
                    if (fileSource.isNotBlank()) cssBuilder.append("\n").append(fileSource)
                }
                val script = "(function(){try{var style=document.createElement('style');style.textContent=" +
                    JSONObject.quote(cssBuilder.toString()) +
                    ";document.documentElement.appendChild(style);return true;}catch(e){return false;}})();"
                webView.evaluateJavascript(script) { completeRuntimeMessage(callbackId, "null") }
            }
        }

        @JavascriptInterface
        fun openTabFor(pluginId: String, url: String, active: Boolean) {
            findPlugin(pluginId) ?: return
            runOnUiThread {
                if (active) {
                    createNewTab(url)
                    activePluginPopup?.dismiss()
                } else {
                    browserTabs.add(BrowserTab(nextTabId++, readableTabTitle(url), url))
                    updateTabsUi()
                }
            }
        }

        @JavascriptInterface
        fun closePopupFor(pluginId: String) {
            findPlugin(pluginId) ?: return
            runOnUiThread { activePluginPopup?.dismiss() }
        }

        @JavascriptInterface
        fun openPopupFor(pluginId: String) {
            val plugin = findPlugin(pluginId) ?: return
            runOnUiThread { showPluginPopup(plugin) }
        }

        @JavascriptInterface
        fun openPopupPathFor(pluginId: String, path: String) {
            val plugin = findPlugin(pluginId) ?: return
            runOnUiThread { showPluginPopup(plugin, path.ifBlank { plugin.popupPath }) }
        }

        @JavascriptInterface
        fun openWindowFor(pluginId: String, url: String, type: String, width: Int, height: Int) {
            val plugin = findPlugin(pluginId) ?: return
            runOnUiThread {
                val path = if (url.isNotBlank() && (!url.startsWith("http") || url.startsWith("https://chrome-extension.local/${plugin.id}/") || url.startsWith("chrome-extension://${plugin.id}/"))) {
                    normalizeExtensionPath(plugin, url)
                } else null
                if (!path.isNullOrBlank() && type.lowercase() == "popup") {
                    showPluginPopup(plugin, path)
                } else if (!path.isNullOrBlank()) {
                    openPluginPageInTab(plugin, path)
                } else if (url.isNotBlank()) {
                    createNewTab(url)
                }
            }
        }

        @JavascriptInterface
        fun openSidePanelFor(pluginId: String, path: String) {
            val plugin = findPlugin(pluginId) ?: return
            runOnUiThread { showPluginSidePanel(plugin, path.ifBlank { plugin.sidePanelPath ?: plugin.popupPath ?: plugin.optionsPage }) }
        }

        @JavascriptInterface
        fun closeSidePanelFor(pluginId: String) {
            findPlugin(pluginId) ?: return
            runOnUiThread { closePluginSidePanel() }
        }

        @JavascriptInterface
        fun updateTabFor(pluginId: String, tabId: Int, propsJson: String) {
            findPlugin(pluginId) ?: return
            runOnUiThread {
                try {
                    val props = JSONObject(propsJson)
                    val url = props.optString("url")
                    if (url.isNotBlank()) {
                        webView.loadUrl(url)
                    }
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun removeTabFor(pluginId: String, tabId: Int) {
            findPlugin(pluginId) ?: return
            runOnUiThread {
                if (browserTabs.size > 1) {
                    closeTab(activeTabIndex)
                }
            }
        }

        @JavascriptInterface
        fun execCommandFor(pluginId: String, command: String, value: String) {
            val plugin = findPlugin(pluginId) ?: return
            if (!plugin.deepAccess || !plugin.allowClipboard) return
            runOnUiThread {
                if (command == "copy" || command == "cut") {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ConsoleFlow Plugin", value))
                }
            }
        }

        @JavascriptInterface
        fun writeClipboardTextFor(pluginId: String, text: String) {
            val plugin = findPlugin(pluginId) ?: return
            if (!plugin.deepAccess || !plugin.allowClipboard) return
            runOnUiThread {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ConsoleFlow Plugin", text))
            }
        }

        @JavascriptInterface
        fun closeExtensionSurfaceFor(pluginId: String) {
            findPlugin(pluginId) ?: return
            runOnUiThread {
                activePluginPopup?.dismiss()
                if (activeSidePanelPluginId == pluginId) closePluginSidePanel()
            }
        }
    }
}
