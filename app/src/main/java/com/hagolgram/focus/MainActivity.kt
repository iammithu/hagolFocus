package com.hagolgram.focus

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Three tabs, each its own long-lived WebView so switching is instant and nothing reloads:
 * 0 = Messages (inbox + chats), 1 = Stories (home with the feed hidden), 2 = Profile.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val BASE = "https://www.instagram.com"
        const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
        val BLOCK = Regex("^/(explore(/.*)?|reels/?)$")
        const val GRAY = "#8E8E8E"
    }

    private val tabs = arrayOfNulls<WebView>(3)
    private val icons = arrayOfNulls<ImageView>(3)
    private var current = 0
    private var preloaded = false
    private var polling = false
    private lateinit var container: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var bottom: LinearLayout
    private val prefs by lazy { getSharedPreferences("focus", MODE_PRIVATE) }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = tabs[current]
                if (w != null && w.canGoBack()) w.goBack()
                else if (current != 0) select(0)
                else finish()
            }
        })
        select(0)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // A reel/post link opened from another app (WhatsApp etc.) is shown in the Stories tab
    private fun handleIntent(i: Intent?) {
        val d = i?.data ?: return
        if (i.action != Intent.ACTION_VIEW) return
        select(1)
        tab(1).loadUrl(d.toString())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------- UI shell ----------------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        container = FrameLayout(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(Color.parseColor("#0095F6"))
        }
        container.addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(View(this).apply { setBackgroundColor(Color.parseColor("#262626")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        val res = intArrayOf(R.drawable.ic_inbox, R.drawable.ic_stories, R.drawable.ic_profile)
        val names = arrayOf("Messages", "Stories", "Profile")
        for (i in 0..2) {
            val iv = ImageView(this).apply {
                setImageResource(res[i])
                contentDescription = names[i]
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setOnClickListener { onTab(i) }
            }
            icons[i] = iv
            row.addView(iv, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        bottom.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        // hide our bar while the keyboard is up so the chat box gets the room
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            root.getWindowVisibleDisplayFrame(r)
            val h = root.rootView.height
            bottom.visibility = if (h - r.bottom > h * 0.15) View.GONE else View.VISIBLE
        }
    }

    private fun onTab(i: Int) {
        if (i != current) { select(i); return }
        val w = tabs[i] ?: return
        if (w.canGoBack()) w.goBack()
        else { w.scrollTo(0, 0); w.evaluateJavascript("window.scrollTo(0,0)", null) }
    }

    private fun select(i: Int) {
        if (tabs[current] != null && current != i) {
            // stop any reel/story audio in the tab we're leaving
            tabs[current]?.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null)
        }
        current = i
        val w = tab(i)
        for (t in tabs) t?.visibility = View.GONE
        w.visibility = View.VISIBLE
        w.requestFocus()
        for (j in 0..2) icons[j]?.setColorFilter(if (j == i) Color.WHITE else Color.parseColor(GRAY))
        progress.visibility = View.GONE
    }

    private fun tab(i: Int): WebView {
        tabs[i]?.let { return it }
        val w = newWebView(i)
        w.visibility = if (i == current) View.VISIBLE else View.GONE
        tabs[i] = w
        container.addView(w, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        when (i) {
            0 -> w.loadUrl("$BASE/direct/inbox/")
            1 -> w.loadUrl("$BASE/")
            else -> loadProfile(w)
        }
        return w
    }

    // ---------------- WebView ----------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(i: Int): WebView {
        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.userAgentString = UA
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.allowFileAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = client(i)
        w.webChromeClient = chrome(i)
        return w
    }

    private fun allowedHost(h: String) =
        h == "instagram.com" || h.endsWith(".instagram.com") || h == "facebook.com" || h.endsWith(".facebook.com") ||
            h.endsWith(".cdninstagram.com") || h.endsWith(".fbcdn.net")

    private fun openExternal(u: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, u)) }
        catch (e: Exception) { Toast.makeText(this, "No app can open that link", Toast.LENGTH_SHORT).show() }
    }

    private fun client(i: Int) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
            val u = r.url
            val scheme = u.scheme ?: ""
            val host = u.host ?: ""
            if (scheme != "http" && scheme != "https") return true            // instagram:// "open the app" links
            if (host == "l.instagram.com") {                                   // external link wrapper
                u.getQueryParameter("u")?.let { openExternal(Uri.parse(it)) }
                return true
            }
            if (!allowedHost(host)) { openExternal(u); return true }
            if (BLOCK.matches(u.path ?: "")) return true                       // no Explore / Reels feed
            return false
        }

        override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
            if (i == current) { progress.progress = 5; progress.visibility = View.VISIBLE }
            v.evaluateJavascript(INJECT_JS, null)
        }

        override fun onPageFinished(v: WebView, url: String?) {
            v.evaluateJavascript(INJECT_JS, null)
            if (i == current) progress.visibility = View.GONE
            CookieManager.getInstance().flush()
            if (!prefs.contains("profile")) pollProfile(v, 0)
            if (!preloaded && i < 2) {
                preloaded = true
                // warm up the other tab in the background so switching is instant
                container.postDelayed({ tab(0); tab(1) }, 1500)
            }
        }
    }

    private fun chrome(i: Int) = object : WebChromeClient() {
        override fun onProgressChanged(v: WebView, p: Int) {
            if (i == current) {
                progress.progress = p
                progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            }
            if (p >= 25) v.evaluateJavascript(INJECT_JS, null)
        }

        override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = cb
            return try {
                fileChooser.launch(params.createIntent())
                true
            } catch (e: Exception) {
                fileCallback = null
                cb.onReceiveValue(null)
                false
            }
        }
    }

    // ---------------- profile tab ----------------
    private fun loadProfile(w: WebView) {
        val p = prefs.getString("profile", null)
        if (p != null) w.loadUrl(BASE + p)
        else { w.loadUrl("$BASE/"); clickProfile(w, 0) }
    }

    /** Learn your own profile path once, from Instagram's own nav bar. */
    private fun pollProfile(w: WebView, tries: Int) {
        if (polling && tries == 0) return
        polling = true
        if (prefs.contains("profile") || tries > 8) { polling = false; return }
        w.evaluateJavascript("(window.__hfProfile&&window.__hfProfile())||''") { r ->
            val p = (r ?: "").trim('"').replace("\\/", "/")
            if (p.startsWith("/")) { prefs.edit().putString("profile", p).apply(); polling = false }
            else w.postDelayed({ pollProfile(w, tries + 1) }, 2000)
        }
    }

    /** First time only: open your profile by tapping the (hidden) profile link on the home page. */
    private fun clickProfile(w: WebView, tries: Int) {
        if (tries > 20) return
        val js = """(function(){var p=window.__hfProfile&&window.__hfProfile();if(p){var a=document.querySelector('a[href="'+p+'"]');if(a){a.click();return p;}}return '';})()"""
        w.evaluateJavascript(js) { r ->
            val p = (r ?: "").trim('"').replace("\\/", "/")
            if (p.startsWith("/")) prefs.edit().putString("profile", p).apply()
            else w.postDelayed({ clickProfile(w, tries + 1) }, 1000)
        }
    }

    // ---------------- lifecycle ----------------
    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        tabs[current]?.onPause()
    }

    override fun onResume() {
        super.onResume()
        tabs[current]?.onResume()
    }

    override fun onDestroy() {
        for (t in tabs) t?.destroy()
        super.onDestroy()
    }
}
