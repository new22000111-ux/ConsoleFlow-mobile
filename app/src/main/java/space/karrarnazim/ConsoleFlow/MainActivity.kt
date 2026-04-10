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
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {
    data class BrowserPlugin(
        val id: String,
        val name: String,
        val matchPattern: String,
        val script: String,
        val enabled: Boolean,
        val deepAccess: Boolean
    )

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var textUrl: EditText
    private lateinit var btnBookmark: ImageView
    private lateinit var imgSearchEngine: ImageView
    private lateinit var findBar: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout

    private lateinit var prefsManager: PrefsManager
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val extensionDownloadClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPermissionRequest: PermissionRequest? = null
    private var chromeStoreCompatMode = false
    private var lastChromeStorePromptUrl: String? = null

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
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> hideCustomView()
                findBar.visibility == View.VISIBLE -> findBar.visibility = View.GONE
                webView.canGoBack() -> webView.goBack()
                else -> finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
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
                updateBookmarkIcon(url ?: "")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.INVISIBLE
                url?.let {
                    if (it != HOME_URL) prefsManager.addHistory(view?.title ?: "Unknown", it)
                }
                // Fallback Eruda injection — XHR + eval bypasses CSP script-src restrictions
                view?.evaluateJavascript(
                    "(function(){if(window.__erudaLoaded)return;window.__erudaLoaded=true;var x=new XMLHttpRequest();x.open('GET','https://eruda.local/eruda.js',true);x.onload=function(){try{eval(x.responseText);eruda.init();}catch(e){}};x.send();})()",
                    null
                )
                if (!url.isNullOrBlank()) {
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

                // Serve Eruda locally to avoid CORS issues
                if (url == "https://eruda.local/eruda.js") {
                    return try {
                        val stream = assets.open("eruda.js")
                        WebResourceResponse("application/javascript", "utf-8", stream)
                    } catch (e: Exception) { null }
                }

                // Skip interception for search engines / major sites — prevents CAPTCHA
                val host = request.url.host ?: ""
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
        webView.evaluateJavascript(
            """
            (function(){
                if (!window.eruda) return "Eruda is not loaded on this page yet";
                if (window.__consoleFlowConsoleVisible === false) {
                    eruda.show();
                    window.__consoleFlowConsoleVisible = true;
                    return "Console opened";
                }
                eruda.hide();
                window.__consoleFlowConsoleVisible = false;
                return "Console hidden";
            })();
            """.trimIndent()
        ) { result ->
            Toast.makeText(this, result?.trim('"') ?: "Done", Toast.LENGTH_SHORT).show()
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
            "$status • ${plugin.name} (${plugin.matchPattern})"
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
        Thread {
            try {
                val updateUrl = ChromeExtensionInstaller.buildCrxDownloadUrl(extensionId)
                val response = extensionDownloadClient.newCall(Request.Builder().url(updateUrl).build()).execute()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download failed (${response.code})")
                }
                val crxBytes = response.body?.bytes() ?: throw IllegalStateException("No data returned")
                val zipBytes = ChromeExtensionInstaller.extractZipFromCrx(crxBytes)
                val payload = ChromeExtensionInstaller.parseCrxPayload(extensionId, zipBytes)
                val plugin = BrowserPlugin(
                    id = payload.extensionId,
                    name = payload.name,
                    matchPattern = payload.matchPattern,
                    script = payload.script,
                    enabled = true,
                    deepAccess = false
                )
                upsertPlugin(plugin)

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Installed: ${plugin.name}. Open Plugin Manager to edit permissions.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showPluginActions(plugin: BrowserPlugin) {
        val options = arrayOf(
            if (plugin.enabled) "Disable" else "Enable",
            "Edit",
            "Delete"
        )
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle(plugin.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        upsertPlugin(plugin.copy(enabled = !plugin.enabled))
                        Toast.makeText(this, "Plugin updated", Toast.LENGTH_SHORT).show()
                    }
                    1 -> showPluginEditor(plugin)
                    2 -> {
                        removePlugin(plugin.id)
                        Toast.makeText(this, "Plugin deleted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
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
            hint = "JavaScript code"
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
                    enabled = enabledSwitch.isChecked,
                    deepAccess = deepAccessSwitch.isChecked
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
                        enabled = obj.optBoolean("enabled", true),
                        deepAccess = obj.optBoolean("deepAccess", false)
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
                    put("enabled", plugin.enabled)
                    put("deepAccess", plugin.deepAccess)
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

    private fun runPluginsForUrl(url: String) {
        val host = Uri.parse(url).host ?: ""
        getPlugins()
            .filter { it.enabled && doesPluginMatch(it.matchPattern, url, host) }
            .forEach { plugin ->
                val wrappedScript = buildString {
                    append("(function(){")
                    append(buildChromeCompatLayer(plugin.id))
                    append("window.ConsoleFlowPlugin={")
                    append("name:${JSONObject.quote(plugin.name)},")
                    append("match:${JSONObject.quote(plugin.matchPattern)},")
                    append("deepAccess:${plugin.deepAccess}")
                    append("};")
                    if (!plugin.deepAccess) {
                        append("window.ConsoleFlowHost=undefined;")
                    }
                    append(plugin.script)
                    append("})();")
                }
                webView.evaluateJavascript(wrappedScript, null)
            }
    }

    private fun buildChromeCompatLayer(extensionId: String): String {
        return """
            if(!window.chrome){window.chrome={};}
            if(!window.chrome.runtime){window.chrome.runtime={};}
            if(!window.chrome.storage){window.chrome.storage={};}
            if(!window.chrome.storage.local){
              window.chrome.storage.local={
                get:function(keys,cb){
                  try{
                    var raw=localStorage.getItem('__cf_store__')||'{}';
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
                    var raw=localStorage.getItem('__cf_store__')||'{}';
                    var data=JSON.parse(raw);
                    Object.keys(items||{}).forEach(function(k){data[k]=items[k];});
                    localStorage.setItem('__cf_store__',JSON.stringify(data));
                  }catch(e){}
                  if(cb)cb();
                },
                remove:function(keys,cb){
                  try{
                    var raw=localStorage.getItem('__cf_store__')||'{}';
                    var data=JSON.parse(raw);
                    var arr=Array.isArray(keys)?keys:[keys];
                    arr.forEach(function(k){delete data[k];});
                    localStorage.setItem('__cf_store__',JSON.stringify(data));
                  }catch(e){}
                  if(cb)cb();
                },
                clear:function(cb){
                  try{localStorage.removeItem('__cf_store__');}catch(e){}
                  if(cb)cb();
                }
              };
            }
            window.chrome.runtime.id = ${JSONObject.quote(extensionId)};
            window.chrome.runtime.getURL = function(path){
              return 'chrome-extension://' + window.chrome.runtime.id + '/' + (path||'');
            };
            if(!window.chrome.runtime.sendMessage){
              window.chrome.runtime.sendMessage=function(message,cb){
                if(cb)cb({ok:false,reason:'background_not_supported_in_webview',echo:message});
              };
            }
            if(!window.browser){window.browser=window.chrome;}
        """.trimIndent()
    }

    private fun doesPluginMatch(pattern: String, url: String, host: String): Boolean {
        if (pattern == "*") return true
        val normalized = pattern.trim().lowercase()
        return host.lowercase().contains(normalized) || url.lowercase().contains(normalized)
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
        fun toast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun copyToClipboard(value: String) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ConsoleFlow Plugin", value))
        }

        @JavascriptInterface
        fun shareText(value: String) {
            runOnUiThread {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, value)
                }
                startActivity(Intent.createChooser(share, "Share from Plugin"))
            }
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Unable to open link", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun currentUrl(): String = webView.url ?: ""
    }
}
