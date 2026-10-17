package com.hagolgram.focus

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

/**
 * Tabs: 0 = Messages (Instagram's inbox, kept alive in its own WebView), 1 = Stories (native tray + viewer),
 * 2 = Profile (WebView). Reels people send you open in our own single-reel player.
 */
class MainActivity : ComponentActivity(), Host {

    companion object {
        const val BASE = "https://www.instagram.com"
        const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
        const val APP_ID = "936619743392459"
        const val GRAY = "#8E8E8E"
        val BLOCK = Regex("^/(explore(/.*)?|reels/?)$")
    }

    private val reelRx = Regex("^/(?:[^/]+/)?reels?/([A-Za-z0-9_-]{5,})/?$")
    private var lastReelCode = ""
    private var lastReelAt = 0L

    /** If this URL is a single-reel page on instagram.com, returns the reel's shortcode. */
    private fun reelCodeOf(url: String?): String? {
        if (url == null) return null
        val u = Uri.parse(url)
        val host = u.host ?: return null
        if (!(host == "instagram.com" || host.endsWith(".instagram.com"))) return null
        val m = reelRx.find(u.path ?: "") ?: return null
        return m.groupValues[1].takeIf { it != "audio" }
    }

    private val webs = arrayOfNulls<WebView>(3)
    private val pages = arrayOfNulls<View>(3)
    private val icons = arrayOfNulls<ImageView>(3)
    private var current = 0
    private var polling = false
    private var overlay: View? = null

    private lateinit var frame: FrameLayout
    private lateinit var container: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var bottom: LinearLayout
    private lateinit var errorLayer: LinearLayout
    private lateinit var msgDot: View
    private lateinit var stories: StoriesTab

    private val apiCallbacks = HashMap<Int, (String) -> Unit>()
    private var reqSeq = 0
    private val prefs by lazy { getSharedPreferences("focus", MODE_PRIVATE) }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    // ---------------- JS <-> app bridge ----------------
    inner class Bridge {
        @JavascriptInterface fun reel(code: String) { runOnUiThread { openReel(code) } }
        @JavascriptInterface fun api(id: Int, body: String) {
            runOnUiThread { runCatching { apiCallbacks.remove(id)?.invoke(body) } }
        }
    }

    /** Runs one of Instagram's own web calls inside the logged-in page and returns the raw text ("" on failure). */
    override fun api(path: String, post: Boolean, cb: (String) -> Unit) {
        val w = webs[0] ?: webs[2]
        if (w == null) { cb(""); return }
        val id = ++reqSeq
        apiCallbacks[id] = cb
        val js = "(function(){var m=(document.cookie.match(/csrftoken=([^;]+)/)||[])[1]||'';" +
            "fetch('" + path + "',{method:'" + (if (post) "POST" else "GET") + "',credentials:'include'," +
            "headers:{'X-IG-App-ID':'" + APP_ID + "','X-CSRFToken':m,'X-Requested-With':'XMLHttpRequest'," +
            "'X-ASBD-ID':'129477','Accept':'*/*'}})" +
            ".then(function(r){return r.text();}).then(function(t){HF.api(" + id + ",t);})" +
            ".catch(function(){HF.api(" + id + ",'');});})();"
        w.evaluateJavascript(js, null)
        container.postDelayed({ runCatching { apiCallbacks.remove(id)?.invoke("") } }, 12000)
    }

    // ---------------- lifecycle ----------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        stories = StoriesTab(this, this) { list, idx -> showOverlay(StoryViewer(this, this, list, idx).also { it.start() }) }
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = webs[current]
                if (overlay != null) closeOverlay()
                else if (w != null && w.canGoBack()) w.goBack()
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

    /** A reel link opened from another app (WhatsApp etc.) plays in the native player. */
    private fun handleIntent(i: Intent?) {
        val d = i?.data ?: return
        if (i.action != Intent.ACTION_VIEW) return
        val path = d.path ?: ""
        select(0)
        val m = Regex("^/(?:[^/]+/)?reels?/([A-Za-z0-9_-]{5,})").find(path)
        if (!path.startsWith("/share/") && m != null && m.groupValues[1] != "audio") openReel(m.groupValues[1])
        else webs[0]?.loadUrl(d.toString())   // share links redirect to the reel; the page script hands it to the player
    }

    private val poll = object : Runnable {
        override fun run() {
            api("/api/v1/direct_v2/inbox/?persistentBadging=true&limit=1", false) { b ->
                val n = try { JSONObject(b).getJSONObject("inbox").optInt("unseen_count", 0) } catch (e: Exception) { -1 }
                if (n >= 0) msgDot.visibility = if (n > 0 && current != 0) View.VISIBLE else View.GONE
            }
            container.postDelayed(this, 45000)
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        container.removeCallbacks(poll)
        (overlay as? Viewer)?.pause()
        webs[current]?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webs[current]?.onResume()
        container.removeCallbacks(poll)
        container.postDelayed(poll, 4000)
    }

    override fun onDestroy() {
        closeOverlayNow()
        for (w in webs) w?.destroy()
        super.onDestroy()
    }

    // ---------------- UI shell ----------------
    private fun buildUi() {
        frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        container = FrameLayout(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(Color.parseColor("#0095F6"))
        }
        container.addView(progress, FrameLayout.LayoutParams(MATCH, dp(3)))

        errorLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
        }
        errorLayer.addView(TextView(this).apply {
            text = "Can't reach Instagram"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        })
        errorLayer.addView(Button(this).apply {
            text = "Retry"
            setOnClickListener { errorLayer.visibility = View.GONE; webs[current]?.reload() }
        })
        container.addView(errorLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(container, LinearLayout.LayoutParams(MATCH, 0, 1f))

        bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(View(this).apply { setBackgroundColor(Color.parseColor("#262626")) }, LinearLayout.LayoutParams(MATCH, 1))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        val res = intArrayOf(R.drawable.ic_inbox, R.drawable.ic_stories, R.drawable.ic_profile)
        val names = arrayOf("Messages", "Stories", "Profile")
        for (i in 0..2) {
            val cell = FrameLayout(this)
            val iv = ImageView(this).apply { setImageResource(res[i]); contentDescription = names[i] }
            cell.addView(iv, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            if (i == 0) {
                msgDot = View(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FF3040")) }
                    visibility = View.GONE
                }
                cell.addView(msgDot, FrameLayout.LayoutParams(dp(9), dp(9), Gravity.CENTER).apply {
                    leftMargin = dp(18); bottomMargin = dp(16)
                })
            }
            cell.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onTab(i) }
            cell.setOnLongClickListener { reloadTab(i); true }   // long-press a tab = refresh it
            icons[i] = iv
            row.addView(cell, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        bottom.addView(row, LinearLayout.LayoutParams(MATCH, dp(52)))
        root.addView(bottom, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(frame)

        // hide our bar while the keyboard is up so the chat box gets the room
        frame.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            frame.getWindowVisibleDisplayFrame(r)
            val h = frame.rootView.height
            bottom.visibility = if (h - r.bottom > h * 0.15) View.GONE else View.VISIBLE
        }
    }

    private fun showOverlay(v: View) {
        closeOverlayNow()
        overlay = v
        frame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        v.translationY = frame.height.toFloat()
        v.animate().translationY(0f).setDuration(200).start()
    }

    /** Silence the viewer at once, slide it away, then free it. */
    override fun closeOverlay() {
        val v = overlay ?: return
        overlay = null
        (v as? Viewer)?.pause()
        v.animate().translationY(frame.height.toFloat()).alpha(0f).setDuration(180).withEndAction {
            (v as? Viewer)?.release()
            frame.removeView(v)
        }.start()
    }

    private fun closeOverlayNow() {
        val v = overlay ?: return
        overlay = null
        (v as? Viewer)?.release()
        frame.removeView(v)
    }

    override fun openProfile(username: String) {
        closeOverlay()
        select(2)
        webs[2]?.loadUrl("$BASE/$username/")
    }

    /** One reel, one player. Ignores duplicate triggers (page script + navigation hook fire together). */
    private fun openReel(code: String) {
        val now = System.currentTimeMillis()
        if (code == lastReelCode && now - lastReelAt < 1500) return
        lastReelCode = code
        lastReelAt = now
        // make sure nothing else is playing sound underneath
        webs[current]?.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null)
        showOverlay(ReelViewer(this, code, this).also { it.start() })
    }

    private fun onTab(i: Int) {
        if (i != current) { select(i); return }
        if (i == 1) { stories.refresh(true); return }
        val w = webs[i] ?: return
        if (w.canGoBack()) w.goBack()
        else { w.scrollTo(0, 0); w.evaluateJavascript("window.scrollTo(0,0)", null) }
    }

    private fun reloadTab(i: Int) {
        if (i == 1) stories.refresh(true) else webs[i]?.reload()
        Toast.makeText(this, "Refreshing…", Toast.LENGTH_SHORT).show()
    }

    private fun select(i: Int) {
        if (current != i) {
            webs[current]?.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null)
        }
        current = i
        val v = page(i)
        for (p in pages) p?.visibility = View.GONE
        v.alpha = 0f
        v.visibility = View.VISIBLE
        v.animate().alpha(1f).setDuration(140).start()
        webs[i]?.requestFocus()
        for (j in 0..2) icons[j]?.setColorFilter(if (j == i) Color.WHITE else Color.parseColor(GRAY))
        progress.visibility = View.GONE
        errorLayer.visibility = View.GONE
        if (i == 0) msgDot.visibility = View.GONE
        if (i == 1) stories.refresh(false)
    }

    private fun page(i: Int): View {
        pages[i]?.let { return it }
        val v: View
        if (i == 1) v = stories.view
        else {
            val w = newWebView(i)
            webs[i] = w
            v = w
        }
        v.visibility = if (i == current) View.VISIBLE else View.GONE
        pages[i] = v
        container.addView(v, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        if (i == 0) webs[0]?.loadUrl("$BASE/direct/inbox/")
        else if (i == 2) webs[2]?.let { loadProfile(it) }
        return v
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
        w.addJavascriptInterface(Bridge(), "HF")
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
            if (scheme != "http" && scheme != "https") return true             // instagram:// / market:// "open the app" links
            if (host == "play.google.com") return true                          // "Get Instagram (Lite)" banners do nothing
            if (host == "l.instagram.com") {                                    // external link wrapper
                u.getQueryParameter("u")?.let { openExternal(Uri.parse(it)) }
                return true
            }
            if (!allowedHost(host)) { openExternal(u); return true }
            reelCodeOf(u.toString())?.let { openReel(it); return true }          // a reel link: native player, never the web reel page
            if (BLOCK.matches(u.path ?: "")) return true                        // no Explore / Reels feed
            return false
        }

        // fires for in-page navigation too: second safety net in case the page script doesn't catch a reel tap
        override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
            val code = reelCodeOf(url) ?: return
            openReel(code)
            v.postDelayed({ if (reelCodeOf(v.url) != null && v.canGoBack()) v.goBack() }, 600)
        }

        override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
            if (i == current) { progress.progress = 5; progress.visibility = View.VISIBLE; errorLayer.visibility = View.GONE }
            v.evaluateJavascript(INJECT_JS, null)
        }

        override fun onPageFinished(v: WebView, url: String?) {
            v.evaluateJavascript(INJECT_JS, null)
            if (i == current) progress.visibility = View.GONE
            CookieManager.getInstance().flush()
            if (!prefs.contains("profile")) pollProfile(v, 0)
        }

        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
            if (r.isForMainFrame && i == current) errorLayer.visibility = View.VISIBLE
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
}
