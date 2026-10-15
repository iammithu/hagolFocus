package com.hagolgram.focus

import java.net.HttpURLConnection
import java.net.URL

/** Last-resort: read the public embed page of a reel and pull out the video file URL. Unofficial, may break. */
object VideoSource {
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val pats = listOf(
        Regex("\"video_url\"\\s*:\\s*\"(https:[^\"]+)\""),
        Regex("<video[^>]*\\ssrc=\"(https:[^\"]+)\""),
        Regex("(https:[^\"'\\s<>\\\\]+\\.mp4[^\"'\\s<>\\\\]*)")
    )

    fun fetchVideoUrl(code: String): String? {
        for (path in listOf("embed/captioned/", "embed/")) {
            try {
                val c = URL("https://www.instagram.com/reel/$code/$path").openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", UA)
                c.connectTimeout = 10000
                c.readTimeout = 10000
                val html = c.inputStream.bufferedReader().use { it.readText() }
                val t = html.replace("\\\"", "\"").replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
                for (p in pats) {
                    val m = p.find(t)
                    if (m != null) return m.groupValues[1].replace("\\", "")
                }
            } catch (e: Exception) { }
        }
        return null
    }
}
