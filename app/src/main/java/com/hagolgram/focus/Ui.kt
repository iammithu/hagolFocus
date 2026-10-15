package com.hagolgram.focus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.ViewGroup
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** "3h", "2d" ... from a unix time in seconds */
fun agoSec(t: Long): String {
    val s = System.currentTimeMillis() / 1000 - t
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h"
        else -> "${s / 86400}d"
    }
}

fun shortcodeToId(code: String): String {
    val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    var n = java.math.BigInteger.ZERO
    for (c in code.take(11)) {
        n = n.multiply(java.math.BigInteger.valueOf(64)).add(java.math.BigInteger.valueOf(maxOf(a.indexOf(c), 0).toLong()))
    }
    return n.toString()
}

/** Tiny image loader: downloads in the background, caches in memory. */
object Img {
    private val pool = Executors.newFixedThreadPool(4)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun load(url: String, target: ImageView, maxW: Int = 1080, done: (() -> Unit)? = null) {
        target.tag = url
        val hit = cache.get(url)
        if (hit != null) { target.setImageBitmap(hit); done?.invoke(); return }
        pool.execute {
            val bmp = try {
                val bytes = URL(url).openStream().use { it.readBytes() }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= maxW) s *= 2
                val o2 = BitmapFactory.Options().apply { inSampleSize = s }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
            } catch (e: Exception) { null }
            if (bmp != null) {
                cache.put(url, bmp)
                target.post {
                    if (target.tag == url) { target.setImageBitmap(bmp); done?.invoke() }
                }
            }
        }
    }
}
