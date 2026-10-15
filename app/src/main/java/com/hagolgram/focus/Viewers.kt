@file:OptIn(UnstableApi::class)

package com.hagolgram.focus

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

interface Host {
    fun api(path: String, post: Boolean, cb: (String) -> Unit)
    fun closeOverlay()
}

interface Viewer {
    fun pause()
    fun release()
}

private fun scrim(): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xB3000000.toInt()))

private fun iconButton(ctx: Context, res: Int, onClick: () -> Unit): ImageView =
    ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
        setOnClickListener { onClick() }
    }

// =====================================================================================
//  Single-reel player: one reel, full screen, no scrolling, no suggestions.
// =====================================================================================
data class ReelInfo(val url: String, val user: String, val caption: String)

class ReelViewer(private val ctx: Context, private val code: String, private val host: Host) : FrameLayout(ctx), Viewer {
    private val exo = ExoPlayer.Builder(ctx).build()
    private val pv = PlayerView(ctx)
    private val tapLayer = View(ctx)
    private val spinner = ProgressBar(ctx)
    private val playIcon = ImageView(ctx)
    private val userTv = TextView(ctx)
    private val capTv = TextView(ctx)
    private val seek = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
    private val muteBtn: ImageView
    private val handler = Handler(Looper.getMainLooper())
    private var muted = false
    private var triedEmbed = false
    private var released = false
    private var web: WebView? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            val d = exo.duration
            if (d > 0) seek.progress = (exo.currentPosition * 1000 / d).toInt()
            handler.postDelayed(this, 100)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        pv.useController = false
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        pv.setShutterBackgroundColor(Color.BLACK)
        pv.player = exo
        addView(pv, LayoutParams(MATCH, MATCH))
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) spinner.visibility = View.GONE
            }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) {
                    pv.resizeMode = if (v.width.toFloat() / v.height < 0.7f) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    else AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
            override fun onPlayerError(error: PlaybackException) { fallbackEmbed() }
        })

        tapLayer.setOnClickListener { toggle() }
        addView(tapLayer, LayoutParams(MATCH, MATCH))

        addView(View(ctx).apply { background = scrim() }, LayoutParams(MATCH, ctx.dp(240), Gravity.BOTTOM))

        playIcon.setImageResource(R.drawable.ic_play)
        playIcon.setColorFilter(Color.WHITE)
        playIcon.visibility = View.GONE
        addView(playIcon, LayoutParams(ctx.dp(84), ctx.dp(84), Gravity.CENTER))

        spinner.indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        addView(spinner, LayoutParams(ctx.dp(36), ctx.dp(36), Gravity.CENTER))

        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        userTv.setTextColor(Color.WHITE); userTv.textSize = 16f
        userTv.setTypeface(userTv.typeface, android.graphics.Typeface.BOLD)
        capTv.setTextColor(Color.WHITE); capTv.textSize = 14f; capTv.maxLines = 3
        capTv.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(userTv); info.addView(capTv)
        addView(info, LayoutParams(MATCH, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = ctx.dp(16); rightMargin = ctx.dp(16); bottomMargin = ctx.dp(44)
        })

        // thin progress line you can drag to seek
        seek.max = 1000
        seek.progressTintList = ColorStateList.valueOf(Color.WHITE)
        seek.progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        val strip = FrameLayout(ctx)
        strip.addView(seek, LayoutParams(MATCH, ctx.dp(3), Gravity.CENTER_VERTICAL))
        strip.setOnTouchListener { v, e ->
            val d = exo.duration
            if (d > 0) {
                val f = (e.x / v.width).coerceIn(0f, 1f)
                exo.seekTo((d * f).toLong())
                seek.progress = (f * 1000).toInt()
            }
            true
        }
        addView(strip, LayoutParams(MATCH, ctx.dp(28), Gravity.BOTTOM).apply { bottomMargin = ctx.dp(6) })

        addView(iconButton(ctx, R.drawable.ic_close) { host.closeOverlay() },
            LayoutParams(ctx.dp(44), ctx.dp(44), Gravity.TOP or Gravity.START).apply { leftMargin = ctx.dp(10); topMargin = ctx.dp(10) })
        muteBtn = iconButton(ctx, R.drawable.ic_volume_on) { toggleMute() }
        addView(muteBtn, LayoutParams(ctx.dp(44), ctx.dp(44), Gravity.TOP or Gravity.END).apply { rightMargin = ctx.dp(10); topMargin = ctx.dp(10) })
    }

    /** 1) Instagram's own media API with your session  2) the public embed page  3) the embed in a web view */
    fun start() {
        host.api("/api/v1/media/${shortcodeToId(code)}/info/", false) { body ->
            if (!released) {
                val info = parseInfo(body)
                if (info != null) play(info) else fallbackEmbed()
            }
        }
    }

    private fun parseInfo(body: String): ReelInfo? = try {
        val item = JSONObject(body).getJSONArray("items").getJSONObject(0)
        val url = item.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url") ?: ""
        if (url.isEmpty()) null
        else ReelInfo(
            url,
            item.optJSONObject("user")?.optString("username") ?: "",
            item.optJSONObject("caption")?.optString("text") ?: ""
        )
    } catch (e: Exception) { null }

    private fun play(i: ReelInfo) {
        userTv.text = if (i.user.isEmpty()) "" else "@" + i.user
        capTv.text = i.caption
        exo.setMediaItem(MediaItem.fromUri(i.url))
        exo.prepare()
        exo.playWhenReady = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun fallbackEmbed() {
        if (triedEmbed || released) return
        triedEmbed = true
        Thread {
            val u = VideoSource.fetchVideoUrl(code)
            post {
                if (!released) {
                    if (u != null) play(ReelInfo(u, "", "")) else showWeb()
                }
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb() {
        spinner.visibility = View.GONE
        tapLayer.visibility = View.GONE
        val w = WebView(ctx)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.setBackgroundColor(Color.BLACK)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = !r.url.toString().contains("/embed")
        }
        addView(w, 1, LayoutParams(MATCH, MATCH))
        w.loadUrl("https://www.instagram.com/reel/$code/embed/")
        web = w
    }

    private fun toggle() {
        if (exo.playWhenReady) { exo.playWhenReady = false; playIcon.visibility = View.VISIBLE }
        else { exo.playWhenReady = true; playIcon.visibility = View.GONE }
    }

    private fun toggleMute() {
        muted = !muted
        exo.volume = if (muted) 0f else 1f
        muteBtn.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
    }

    override fun pause() { exo.playWhenReady = false; playIcon.visibility = View.VISIBLE }

    override fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        pv.player = null
        exo.release()
        web?.destroy()
    }
}

// =====================================================================================
//  Story viewer: segmented progress, tap right/left, hold to pause, swipe down to close.
// =====================================================================================
data class Story(val video: String?, val image: String?, val takenAt: Long, val durationMs: Long)

class StoryViewer(private val ctx: Context, private val host: Host, private val trays: List<Tray>, start: Int) :
    FrameLayout(ctx), Viewer {

    private var ti = start
    private var items: List<Story> = emptyList()
    private var ii = 0
    private var gen = 0
    private var ready = false
    private var paused = false
    private var released = false
    private var itemStart = 0L
    private var pausedAt = 0L
    private var itemDur = 5000L
    private var isVideo = false

    private val handler = Handler(Looper.getMainLooper())
    private val exo = ExoPlayer.Builder(ctx).build()
    private val pv = PlayerView(ctx)
    private val img = ImageView(ctx)
    private val spinner = ProgressBar(ctx)
    private val bars = LinearLayout(ctx)
    private val avatar = ImageView(ctx)
    private val nameTv = TextView(ctx)
    private val timeTv = TextView(ctx)
    private val msg = TextView(ctx)

    private val hold = Runnable { pauseStory() }
    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            if (!paused && ready && items.isNotEmpty()) {
                val frac = if (isVideo) {
                    val d = exo.duration
                    if (d > 0) exo.currentPosition.toFloat() / d else 0f
                } else (SystemClock.uptimeMillis() - itemStart).toFloat() / itemDur
                setBar(frac)
                if (frac >= 1f) { next(); return }
            }
            handler.postDelayed(this, 33)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        pv.useController = false
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        pv.setShutterBackgroundColor(Color.BLACK)
        pv.player = exo
        pv.visibility = View.GONE
        addView(pv, LayoutParams(MATCH, MATCH))
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(img, LayoutParams(MATCH, MATCH))

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { ready = true; spinner.visibility = View.GONE }
                if (state == Player.STATE_ENDED) next()
            }
            override fun onPlayerError(error: PlaybackException) { next() }
        })

        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x00000000, 0x99000000.toInt()))
        }, LayoutParams(MATCH, ctx.dp(140), Gravity.TOP))

        spinner.indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        addView(spinner, LayoutParams(ctx.dp(36), ctx.dp(36), Gravity.CENTER))

        msg.setTextColor(Color.WHITE); msg.textSize = 15f; msg.gravity = Gravity.CENTER
        msg.visibility = View.GONE
        addView(msg, LayoutParams(MATCH, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        bars.orientation = LinearLayout.HORIZONTAL
        addView(bars, LayoutParams(MATCH, ctx.dp(3), Gravity.TOP).apply {
            leftMargin = ctx.dp(8); rightMargin = ctx.dp(8); topMargin = ctx.dp(8)
        })

        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        avatar.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF333333.toInt()) }
        avatar.clipToOutline = true
        header.addView(avatar, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)))
        nameTv.setTextColor(Color.WHITE); nameTv.textSize = 14f
        nameTv.setTypeface(nameTv.typeface, android.graphics.Typeface.BOLD)
        timeTv.setTextColor(0xCCFFFFFF.toInt()); timeTv.textSize = 13f
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        texts.addView(nameTv)
        texts.addView(timeTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ctx.dp(8) })
        header.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ctx.dp(10) })
        addView(header, LayoutParams(MATCH, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = ctx.dp(12); rightMargin = ctx.dp(56); topMargin = ctx.dp(20)
        })

        // gestures live on their own layer, below the close button
        val touch = View(ctx)
        var downX = 0f
        var downY = 0f
        touch.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; handler.postDelayed(hold, 220) }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(hold)
                    if (paused) resumeStory()
                    else {
                        val dy = e.y - downY
                        if (dy > ctx.dp(120)) host.closeOverlay()
                        else if (abs(dy) < ctx.dp(30) && abs(e.x - downX) < ctx.dp(30)) {
                            if (e.x < v.width * 0.33f) prev() else next()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(hold); if (paused) resumeStory() }
            }
            true
        }
        addView(touch, 1, LayoutParams(MATCH, MATCH))

        addView(iconButton(ctx, R.drawable.ic_close) { host.closeOverlay() },
            LayoutParams(ctx.dp(44), ctx.dp(44), Gravity.TOP or Gravity.END).apply { rightMargin = ctx.dp(8); topMargin = ctx.dp(12) })
    }

    fun start() { loadTray() }

    private fun loadTray() {
        val t = trays[ti]
        gen++
        val g = gen
        handler.removeCallbacks(ticker)
        exo.stop()
        ready = false
        items = emptyList()
        msg.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        nameTv.text = t.username
        timeTv.text = ""
        Img.load(t.avatar, avatar, 200)
        buildBars(0)
        host.api("/api/v1/feed/reels_media/?reel_ids=${t.userId}", false) { body ->
            if (!released && g == gen) {
                val list = parseStories(body, t.userId)
                if (list.isEmpty()) { spinner.visibility = View.GONE; next() }
                else { items = list; ii = 0; buildBars(list.size); startItem() }
            }
        }
    }

    private fun parseStories(body: String, userId: String): List<Story> {
        return try {
            val j = JSONObject(body)
            val arr = j.optJSONArray("reels_media")?.optJSONObject(0)?.optJSONArray("items")
                ?: j.optJSONObject("reels")?.optJSONObject(userId)?.optJSONArray("items")
                ?: return emptyList()
            val out = ArrayList<Story>()
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val video = o.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url")
                val image = o.optJSONObject("image_versions2")?.optJSONArray("candidates")?.optJSONObject(0)?.optString("url")
                if (video.isNullOrEmpty() && image.isNullOrEmpty()) continue
                out.add(Story(if (video.isNullOrEmpty()) null else video, image, o.optLong("taken_at"),
                    (o.optDouble("video_duration", 5.0) * 1000).toLong()))
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    private fun buildBars(n: Int) {
        bars.removeAllViews()
        for (k in 0 until n) {
            val b = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
            b.max = 1000
            b.progressTintList = ColorStateList.valueOf(Color.WHITE)
            b.progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
            bars.addView(b, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (k > 0) leftMargin = ctx.dp(3) })
        }
    }

    private fun setBar(frac: Float) {
        for (k in 0 until bars.childCount) {
            val b = bars.getChildAt(k) as ProgressBar
            b.progress = when {
                k < ii -> 1000
                k == ii -> (frac.coerceIn(0f, 1f) * 1000).toInt()
                else -> 0
            }
        }
    }

    private fun startItem() {
        val s = items[ii]
        handler.removeCallbacks(ticker)
        ready = false
        paused = false
        timeTv.text = agoSec(s.takenAt)
        setBar(0f)
        spinner.visibility = View.VISIBLE
        if (s.video != null) {
            isVideo = true
            img.visibility = View.GONE
            pv.visibility = View.VISIBLE
            exo.setMediaItem(MediaItem.fromUri(s.video))
            exo.prepare()
            exo.playWhenReady = true
        } else {
            isVideo = false
            exo.stop()
            pv.visibility = View.GONE
            img.visibility = View.VISIBLE
            img.setImageDrawable(null)
            itemDur = 5000
            val url = s.image ?: ""
            val g = gen
            val idx = ii
            Img.load(url, img, 1080) {
                if (!released && g == gen && idx == ii) {
                    ready = true
                    itemStart = SystemClock.uptimeMillis()
                    spinner.visibility = View.GONE
                }
            }
        }
        handler.post(ticker)
    }

    private fun next() {
        if (ii < items.size - 1) { ii++; startItem() }
        else if (ti < trays.size - 1) { ti++; loadTray() }
        else host.closeOverlay()
    }

    private fun prev() {
        if (ii > 0) { ii--; startItem() }
        else if (ti > 0) { ti--; loadTray() }
        else startItem()
    }

    private fun pauseStory() {
        if (paused) return
        paused = true
        pausedAt = SystemClock.uptimeMillis()
        exo.playWhenReady = false
    }

    private fun resumeStory() {
        if (!paused) return
        paused = false
        itemStart += SystemClock.uptimeMillis() - pausedAt
        if (isVideo) exo.playWhenReady = true
    }

    override fun pause() { pauseStory() }

    override fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        pv.player = null
        exo.release()
    }
}
