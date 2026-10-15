package com.hagolgram.focus

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

data class Tray(val userId: String, val username: String, val avatar: String, val seen: Boolean)

/** Native story tray: your friends' stories only, no feed. Data comes from Instagram's own story-tray call using your session. */
class StoriesTab(private val ctx: Context, private val host: Host, private val onOpen: (List<Tray>, Int) -> Unit) {

    private val scroll = ScrollView(ctx)
    private val content = LinearLayout(ctx)
    private val status = TextView(ctx)
    private var tray: List<Tray> = emptyList()
    private var lastLoad = 0L
    private var loading = false

    val view: View get() = scroll

    init {
        scroll.setBackgroundColor(Color.BLACK)
        scroll.isVerticalScrollBarEnabled = false
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(ctx.dp(8), ctx.dp(16), ctx.dp(8), ctx.dp(24))
        scroll.addView(content, FrameLayout.LayoutParams(MATCH, FrameLayout.LayoutParams.WRAP_CONTENT))
        status.setTextColor(0xFFA8A8A8.toInt())
        status.textSize = 15f
        status.gravity = Gravity.CENTER
        status.setPadding(ctx.dp(24), ctx.dp(80), ctx.dp(24), ctx.dp(24))
        render()
    }

    fun refresh(force: Boolean) {
        if (loading) return
        if (!force && tray.isNotEmpty() && System.currentTimeMillis() - lastLoad < 60_000) return
        loading = true
        if (tray.isEmpty()) { status.text = "Loading stories…"; render() }
        host.api("/api/v1/feed/reels_tray/", false) { body ->
            val first = parse(body)
            if (first.isNotEmpty()) done(first)
            else host.api("/api/v1/feed/reels_tray/", true) { b2 -> done(parse(b2)) }
        }
    }

    private fun done(list: List<Tray>) {
        loading = false
        lastLoad = System.currentTimeMillis()
        if (list.isNotEmpty()) tray = list
        else if (tray.isEmpty()) status.text = "Couldn't load stories.\nLog in from the Messages tab first, then tap here to retry."
        render()
    }

    private fun parse(body: String): List<Tray> = try {
        val arr = JSONObject(body).optJSONArray("tray")
        val out = ArrayList<Tray>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val u = o.optJSONObject("user") ?: continue
            if (o.has("ad_action") || o.optBoolean("is_ad", false)) continue
            val id = u.optString("pk", u.optString("id", ""))
            val name = u.optString("username", "")
            if (id.isEmpty() || name.isEmpty()) continue
            out.add(Tray(id, name, u.optString("profile_pic_url", ""), o.optLong("seen", 0) >= o.optLong("latest_reel_media", 1)))
        }
        out
    } catch (e: Exception) { emptyList() }

    private fun render() {
        content.removeAllViews()
        if (tray.isEmpty()) {
            status.setOnClickListener { refresh(true) }
            content.addView(status)
            return
        }
        val title = TextView(ctx)
        title.text = "Stories"
        title.setTextColor(Color.WHITE)
        title.textSize = 24f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.setPadding(ctx.dp(8), 0, 0, ctx.dp(16))
        content.addView(title)
        var row: LinearLayout? = null
        for (i in tray.indices) {
            if (i % 4 == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                content.addView(row, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.dp(16) })
            }
            row!!.addView(cell(i), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        // pad the last row so cells keep their width
        val rem = tray.size % 4
        if (rem != 0) for (k in rem until 4) row!!.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
    }

    private fun cell(i: Int): View {
        val t = tray[i]
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val ring = FrameLayout(ctx)
        ring.background = if (t.seen) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF3A3A3A.toInt()) }
        else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFF9CE34.toInt(), 0xFFEE2A7B.toInt(), 0xFF6228D7.toInt())).apply { shape = GradientDrawable.OVAL }
        val inner = FrameLayout(ctx)
        inner.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
        ring.addView(inner, FrameLayout.LayoutParams(ctx.dp(70), ctx.dp(70), Gravity.CENTER))
        val iv = ImageView(ctx)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setBackgroundColor(0xFF262626.toInt())
        iv.clipToOutline = true
        iv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
        }
        inner.addView(iv, FrameLayout.LayoutParams(ctx.dp(62), ctx.dp(62), Gravity.CENTER))
        if (t.avatar.isNotEmpty()) Img.load(t.avatar, iv, 200)
        col.addView(ring, LinearLayout.LayoutParams(ctx.dp(76), ctx.dp(76)))
        val name = TextView(ctx)
        name.text = t.username
        name.textSize = 12f
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        name.gravity = Gravity.CENTER
        name.setTextColor(if (t.seen) 0xFF8E8E8E.toInt() else Color.WHITE)
        name.setPadding(ctx.dp(2), ctx.dp(6), ctx.dp(2), 0)
        col.addView(name, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.setOnClickListener { onOpen(tray, i) }
        return col
    }
}
