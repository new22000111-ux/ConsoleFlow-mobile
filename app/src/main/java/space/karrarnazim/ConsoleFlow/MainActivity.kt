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
    private val pluginBackgroundRuntimes = ConcurrentHashMap<String, WebView>()

    private val HOME_URL = "file:///android_asset/home.html"
    private val ERROR_URL = "file:///android_asset/error.html"
    private val CHROME_STORE_URL = "https://chromewebstore.google.com/"

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
        webView.addJavascriptInterface(PluginBridge(), "ConsoleFlowHost")

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
        browserTabs.forEachIndexed { index, tab ->
            val chip = TextView(this).apply {
                text = "${tab.title.ifBlank { readableTabTitle(tab.url) }.take(18)}  ×"
                setTextColor(if (index == activeTabIndex) 0xFFFFFFFF.toInt() else 0xFFB7C3D4.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(12), 0, dp(12), 0)
                background = getDrawable(
                    if (index == activeTabIndex) R.drawable.browser_tab_active_bg
                    else R.drawable.browser_tab_inactive_bg
                )
                setOnClickListener { switchToTab(index) }
                setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP && event.x >= view.width - dp(34)) {
                        closeTab(index)
                        true
                    } else {
                        false
                    }
                }
                setOnLongClickListener {
                    closeTab(index)
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(dp(132), dp(32)).apply {
                marginEnd = dp(6)
            }
            tabsContainer.addView(chip, lp)
        }
        tabScroll.post { tabScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }
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
        val plugins = getPlugins()
        val labels = mutableListOf(
            "➕ Add Plugin",
            "🧩 Install from Chrome Store URL",
            "🗑 Clear All Plugins"
        )
        labels.addAll(plugins.map { plugin ->
            val status = if (plugin.enabled) "ON" else "OFF"
            val health = if (pluginLastError[plugin.id] != null) "⚠" else "✓"
            "$status $health • ${plugin.name} (${plugin.matchPattern})"
        })

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Plugin Manager")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showPluginEditor(null)
                    1 -> showInstallFromChromeStoreDialog()
                    2 -> {
                        prefsManager.pluginsJson = "[]"
                        Toast.makeText(this, "All plugins removed", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        val pluginIndex = which - 3
                        if (pluginIndex >= 0 && pluginIndex < plugins.size) {
                            showPluginActions(plugins[pluginIndex])
                        }
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
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
                    packageZipBase64 = Base64.encodeToString(zipBytes, Base64.NO_WRAP),
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
            removePlugin(plugin.id)
            pluginBackgroundRuntimes.remove(plugin.id)?.destroy()
            pluginLastError.remove(plugin.id)
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
        val encodedZip = plugin.packageZipBase64 ?: return
        val zipBytes = try {
            Base64.decode(encodedZip, Base64.DEFAULT)
        } catch (_: Exception) {
            return
        }
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
            addJavascriptInterface(SearchBridge(), "Android")
            addJavascriptInterface(PluginBridge(), "ConsoleFlowHost")
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

        if (host == "chrome-extension.local") {
            val extensionId = request.url.pathSegments.firstOrNull().orEmpty()
            val path = URLDecoder.decode(request.url.pathSegments.drop(1).joinToString("/"), "UTF-8")
            if (extensionId.isNotBlank() && path.isNotBlank()) {
                val plugin = findPlugin(extensionId)
                val zipBase64 = plugin?.packageZipBase64
                if (!zipBase64.isNullOrBlank()) {
                    return try {
                        val zipBytes = Base64.decode(zipBase64, Base64.DEFAULT)
                        val fileBytes = ChromeExtensionInstaller.extractFileFromZip(zipBytes, path)
                        if (fileBytes != null) {
                            WebResourceResponse(
                                guessMimeType(path),
                                "utf-8",
                                ByteArrayInputStream(fileBytes)
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
        if (plugin.packageZipBase64.isNullOrBlank()) {
            Toast.makeText(this, "Plugin package files are missing", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun createExtensionPageWebView(plugin: BrowserPlugin, closeScript: String): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            setBackgroundColor(0xFF111820.toInt())
            addJavascriptInterface(SearchBridge(), "Android")
            addJavascriptInterface(PluginBridge(), "ConsoleFlowHost")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return serveLocalBrowserRequest(request) ?: super.shouldInterceptRequest(view, request)
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

        val width = minOf(dp(420), (resources.displayMetrics.widthPixels * 0.92f).toInt()).coerceAtLeast(dp(220))
        val height = minOf(dp(560), (resources.displayMetrics.heightPixels * 0.72f).toInt()).coerceAtLeast(dp(180))
        val container = FrameLayout(this).apply {
            background = getDrawable(R.drawable.browser_popup_bg)
            setPadding(dp(1), dp(1), dp(1), dp(1))
            addView(popupWebView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        activePluginPopup = PopupWindow(container, width, height, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = dp(10).toFloat()
            setOnDismissListener {
                popupWebView.destroy()
                if (activePluginPopup === this) activePluginPopup = null
            }
            showAtLocation(webView, Gravity.TOP or Gravity.END, dp(10), dp(106))
        }
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
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 8)
        }

        val nameInput = EditText(this).apply {
            hint = "Plugin name"
            setText(existing?.name ?: "")
        }
        val matchInput = EditText(this).apply {
            hint = "Match host/path (example: github.com or *)"
            setText(existing?.matchPattern ?: "*")
        }
        val scriptInput = EditText(this).apply {
            hint = "JavaScript code (use ConsoleFlowApi.toast/copyToClipboard/shareText/openExternal/currentUrl)"
            setText(existing?.script ?: "")
            isSingleLine = false
            minLines = 8
        }
        val deepAccessSwitch = Switch(this).apply {
            text = "Deep host access (Clipboard/Share/Open app/Toast)"
            isChecked = existing?.deepAccess ?: false
        }
        val enabledSwitch = Switch(this).apply {
            text = "Enabled"
            isChecked = existing?.enabled ?: true
        }

        container.addView(nameInput)
        container.addView(matchInput)
        container.addView(scriptInput)
        container.addView(deepAccessSwitch)
        container.addView(enabledSwitch)

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle(if (existing == null) "Create Plugin" else "Edit Plugin")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { "Plugin" }
                val match = matchInput.text.toString().trim().ifEmpty { "*" }
                val script = scriptInput.text.toString()
                if (script.isBlank()) {
                    Toast.makeText(this, "Script cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
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
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getPlugins(): MutableList<BrowserPlugin> {
        val list = mutableListOf<BrowserPlugin>()
        val arr = try {
            JSONArray(prefsManager.pluginsJson)
        } catch (e: Exception) {
            JSONArray()
        }
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                list.add(
                    BrowserPlugin(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        name = obj.optString("name", "Plugin"),
                        matchPattern = obj.optString("matchPattern", "*"),
                        script = obj.optString("script", ""),
                        popupPath = obj.optString("popupPath", "").ifBlank { null },
                        sidePanelPath = obj.optString("sidePanelPath", "").ifBlank { null },
                        optionsPage = obj.optString("optionsPage", "").ifBlank { null },
                        packageZipBase64 = obj.optString("packageZipBase64", "").ifBlank { null },
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
                    put("packageZipBase64", plugin.packageZipBase64 ?: "")
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
    }

    private fun upsertPlugin(plugin: BrowserPlugin) {
        val plugins = getPlugins()
        val existingIndex = plugins.indexOfFirst { it.id == plugin.id }
        if (existingIndex >= 0) plugins[existingIndex] = plugin else plugins.add(plugin)
        savePlugins(plugins)
    }

    private fun removePlugin(id: String) {
        val plugins = getPlugins().filter { it.id != id }
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
        return """
            (function(){
              if(!window.__cfExtBus){window.__cfExtBus={listeners:{}};}
              function addListener(channel, fn){
                if(!window.__cfExtBus.listeners[channel]) window.__cfExtBus.listeners[channel]=[];
                window.__cfExtBus.listeners[channel].push(fn);
              }
              function emit(channel, payload){
                var list=(window.__cfExtBus.listeners[channel]||[]).slice();
                list.forEach(function(fn){
                  try{ fn(payload,function(){}); }catch(e){}
                });
              }
            
              if(!window.chrome){window.chrome={};}
              if(!window.chrome.runtime){window.chrome.runtime={};}
              if(!window.chrome.storage){window.chrome.storage={};}
              if(!window.chrome.tabs){window.chrome.tabs={};}
              if(!window.chrome.action){window.chrome.action={};}
              if(!window.chrome.browserAction){window.chrome.browserAction={};}
              if(!window.chrome.sidePanel){window.chrome.sidePanel={};}
              if(!window.chrome.windows){window.chrome.windows={};}
            
              var storeKey='__cf_store__' + ${JSONObject.quote(extensionId)};
              if(!window.chrome.storage.local){
                window.chrome.storage.local={
                  get:function(keys,cb){
                    try{
                      var raw=localStorage.getItem(storeKey)||'{}';
                      var data=JSON.parse(raw);
                      var out={};
                      if(Array.isArray(keys)){keys.forEach(function(k){out[k]=data[k];});}
                      else if(typeof keys==='string'){out[keys]=data[keys];}
                      else if(keys&&typeof keys==='object'){
                        Object.keys(keys).forEach(function(k){out[k]=data[k]===undefined?keys[k]:data[k];});
                      }else{out=data;}
                      if(cb)cb(out);
                    }catch(e){if(cb)cb({});}
                  },
                  set:function(items,cb){
                    try{
                      var raw=localStorage.getItem(storeKey)||'{}';
                      var data=JSON.parse(raw);
                      Object.keys(items||{}).forEach(function(k){data[k]=items[k];});
                      localStorage.setItem(storeKey,JSON.stringify(data));
                    }catch(e){}
                    if(cb)cb();
                  },
                  remove:function(keys,cb){
                    try{
                      var raw=localStorage.getItem(storeKey)||'{}';
                      var data=JSON.parse(raw);
                      var arr=Array.isArray(keys)?keys:[keys];
                      arr.forEach(function(k){delete data[k];});
                      localStorage.setItem(storeKey,JSON.stringify(data));
                    }catch(e){}
                    if(cb)cb();
                  },
                  clear:function(cb){
                    try{localStorage.removeItem(storeKey);}catch(e){}
                    if(cb)cb();
                  }
                };
              }
            
              window.chrome.runtime.id = ${JSONObject.quote(extensionId)};
              window.chrome.runtime.getURL = function(path){
                return 'https://chrome-extension.local/' + window.chrome.runtime.id + '/' + String(path||'').replace(/^\/+/,'');
              };
            
              if(!window.chrome.runtime.onMessage){
                window.chrome.runtime.onMessage={
                  addListener:function(fn){ addListener('runtime:onMessage', fn); }
                };
              }
              if(!window.chrome.runtime.onInstalled){
                window.chrome.runtime.onInstalled={
                  addListener:function(fn){ addListener('runtime:onInstalled', fn); }
                };
              }
              if(!window.chrome.runtime.sendMessage){
                window.chrome.runtime.sendMessage=function(message,cb){
                  var payload={message:message,sender:{id:window.chrome.runtime.id,url:location.href}};
                  emit('runtime:onMessage', payload);
                  if(cb)cb({ok:true,delivered:true});
                };
              }
            
              if(!window.chrome.tabs.query){
                window.chrome.tabs.query=function(queryInfo, cb){
                  var tab={id:1,active:true,currentWindow:true,url:location.href,title:document.title||''};
                  if(cb)cb([tab]);
                };
              }
              if(!window.chrome.tabs.sendMessage){
                window.chrome.tabs.sendMessage=function(tabId, message, cb){
                  window.chrome.runtime.sendMessage({tabId:tabId,message:message}, cb);
                };
              }
              if(!window.chrome.tabs.create){
                window.chrome.tabs.create=function(createProperties, cb){
                  var targetUrl=(createProperties&&createProperties.url)||'about:blank';
                  try{
                    if(window.ConsoleFlowHost){
                      ConsoleFlowHost.openTabFor(window.chrome.runtime.id, targetUrl, !(createProperties&&createProperties.active===false));
                    }
                  }catch(e){}
                  if(cb)cb({id:Date.now(),active:!(createProperties&&createProperties.active===false),url:targetUrl});
                };
              }
            


              if(!window.chrome.windows.create){
                window.chrome.windows.create=function(createData, cb){
                  var targetUrl=(createData&&createData.url)||'';
                  if(Array.isArray(targetUrl)){targetUrl=targetUrl[0]||'';}
                  try{if(window.ConsoleFlowHost)ConsoleFlowHost.openWindowFor(window.chrome.runtime.id,targetUrl,String((createData&&createData.type)||'popup'),Number((createData&&createData.width)||0),Number((createData&&createData.height)||0));}catch(e){}
                  if(cb)cb({id:Date.now(),focused:true,type:(createData&&createData.type)||'popup',tabs:[{id:Date.now()+1,url:targetUrl}]});
                };
              }
              if(!window.chrome.windows.getCurrent){
                window.chrome.windows.getCurrent=function(_, cb){if(typeof _==='function'){cb=_;} if(cb)cb({id:1,focused:true,type:'normal'});};
              }
              if(!window.chrome.windows.remove){
                window.chrome.windows.remove=function(windowId, cb){try{if(window.ConsoleFlowHost)ConsoleFlowHost.closeExtensionSurfaceFor(window.chrome.runtime.id);}catch(e){} if(cb)cb();};
              }

              var sidePanelPath = window.__cfSidePanelPath || ${JSONObject.quote(defaultSidePanelPath)};
              if(!window.chrome.sidePanel.setOptions){
                window.chrome.sidePanel.setOptions=function(options, cb){
                  if(options && options.path){sidePanelPath=options.path;window.__cfSidePanelPath=sidePanelPath;}
                  if(cb)cb();
                };
              }
              if(!window.chrome.sidePanel.getOptions){
                window.chrome.sidePanel.getOptions=function(options, cb){
                  if(cb)cb({enabled:!!sidePanelPath,path:sidePanelPath||''});
                };
              }
              if(!window.chrome.sidePanel.open){
                window.chrome.sidePanel.open=function(options, cb){
                  try{if(window.ConsoleFlowHost)ConsoleFlowHost.openSidePanelFor(window.chrome.runtime.id, sidePanelPath||'');}catch(e){}
                  if(cb)cb();
                };
              }
              if(!window.chrome.sidePanel.setPanelBehavior){
                window.chrome.sidePanel.setPanelBehavior=function(options, cb){
                  window.__cfOpenPanelOnActionClick=!!(options&&options.openPanelOnActionClick);
                  if(cb)cb();
                };
              }

              var popupPath = window.__cfActionPopup || ${JSONObject.quote(defaultPopupPath)};
              function setupActionApi(target){
                if(!target.onClicked){
                  target.onClicked={addListener:function(fn){ addListener('action:onClicked', fn); }};
                }
                if(!target.setBadgeText){target.setBadgeText=function(_,cb){if(cb)cb();};}
                if(!target.setTitle){target.setTitle=function(_,cb){if(cb)cb();};}
                if(!target.setIcon){target.setIcon=function(_,cb){if(cb)cb();};}
                if(!target.setPopup){target.setPopup=function(details,cb){popupPath=(details&&details.popup)||'';window.__cfActionPopup=popupPath;if(cb)cb();};}
                if(!target.getPopup){target.getPopup=function(_,cb){if(cb)cb(popupPath||'');};}
                if(!target.openPopup){target.openPopup=function(options,cb){try{if(window.ConsoleFlowHost)ConsoleFlowHost.openPopupPathFor(window.chrome.runtime.id,popupPath||'');}catch(e){} if(cb)cb();};}
              }
              setupActionApi(window.chrome.action);
              setupActionApi(window.chrome.browserAction);
            
              if(!window.browser){window.browser=window.chrome;}
            
              if(!window.__cfInstalledFired){
                window.__cfInstalledFired=true;
                setTimeout(function(){
                  emit('runtime:onInstalled',{reason:'install'});
                },0);
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

    inner class PluginBridge {
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
        fun closeExtensionSurfaceFor(pluginId: String) {
            findPlugin(pluginId) ?: return
            runOnUiThread {
                activePluginPopup?.dismiss()
                if (activeSidePanelPluginId == pluginId) closePluginSidePanel()
            }
        }
    }
}
