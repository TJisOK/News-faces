package com.tjisok.newsfaces

import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

data class RawItem(val title: String, val link: String, val date: String, val img: String, val imgWidth: Int)

object FeedFetcher {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val MEDIA_NS = "http://search.yahoo.com/mrss/"

    fun fetch(feed: Feed): List<RawItem> {
        val req = Request.Builder().url(feed.url).header("User-Agent", "Mozilla/5.0 (Android) NewsFaces/0.1").header("Accept", "application/rss+xml, application/xml, text/xml, */*").build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            return parse(res.body?.string() ?: "")
        }
    }

    private fun parse(xml: String): List<RawItem> {
        val out = ArrayList<RawItem>()
        val factory = XmlPullParserFactory.newInstance(); factory.isNamespaceAware = true
        val p = factory.newPullParser(); p.setInput(StringReader(xml))
        var inItem = false
        var title = ""; var link = ""; var date = ""; var desc = ""; var best = ""; var bestW = -1
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val name = p.name; val ns = p.namespace ?: ""
                if (name == "item" || name == "entry") { inItem = true; title = ""; link = ""; date = ""; desc = ""; best = ""; bestW = -1 }
                else if (inItem) {
                    when {
                        (name == "content" || name == "thumbnail") && ns == MEDIA_NS -> {
                            val url = p.getAttributeValue(null, "url"); val type = p.getAttributeValue(null, "type") ?: ""; val medium = p.getAttributeValue(null, "medium") ?: ""
                            val w = p.getAttributeValue(null, "width")?.toIntOrNull() ?: 0
                            if (url != null && (type.isEmpty() || type.startsWith("image")) && (medium.isEmpty() || medium == "image") && w > bestW) { best = url; bestW = w }
                        }
                        name == "enclosure" && ns.isEmpty() -> {
                            val url = p.getAttributeValue(null, "url"); val type = p.getAttributeValue(null, "type") ?: ""
                            if (url != null && (type.startsWith("image") || Regex("\\.(jpe?g|png|webp)", RegexOption.IGNORE_CASE).containsMatchIn(url)) && bestW < 0) { best = url; bestW = 0 }
                        }
                        name == "title" && ns.isEmpty() -> title = p.nextText()
                        name == "link" && ns.isEmpty() -> { val href = p.getAttributeValue(null, "href"); link = href ?: p.nextText() }
                        (name == "pubDate" || name == "published" || name == "updated" || name == "date") -> date = p.nextText()
                        (name == "description" || name == "encoded") -> desc = p.nextText()
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && (p.name == "item" || p.name == "entry") && inItem) {
                inItem = false
                if (best.isEmpty()) { val m = Regex("<img[^>]+src=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(desc); if (m != null) best = m.groupValues[1] }
                if (best.isNotEmpty()) out.add(RawItem(title, link, date, best, bestW))
            }
            ev = p.next()
        }
        return out
    }

    private val formats = listOf(DateTimeFormatter.RFC_1123_DATE_TIME, DateTimeFormatter.ISO_OFFSET_DATE_TIME, DateTimeFormatter.ISO_ZONED_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH), DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH))

    fun parseDate(s: String): Long {
        val t = s.trim(); if (t.isEmpty()) return System.currentTimeMillis()
        for (f in formats) { try { return ZonedDateTime.parse(t, f).toInstant().toEpochMilli() } catch (_: Exception) {} }
        return System.currentTimeMillis()
    }

    fun upgradeImg(u0: String): String {
        var u = u0.trim().replace("&amp;", "&")
        u = u.replace(Regex("(ichef\\.bbci\\.co\\.uk/ace/standard/)\\d+/"), "$11024/").replace(Regex("(ichef\\.bbci\\.co\\.uk/news/)\\d+/"), "$11024/")
        u = u.replace(Regex("(s\\.france24\\.com/media/display/[^/]+/)w:\\d+/"), "$1w:1280/")
        return u
    }

    fun keyOf(img: String, link: String): String = (img.ifEmpty { link }).replace(Regex("^https?://"), "").replace(Regex("[?#].*$"), "")

    /** Guardian RSS carries a 140px and a 460px rendition; the master is public on media.guim.co.uk and can be sized freely. */
    fun guardianMaster(u: String): String? {
        val m = Regex("i\\.guim\\.co\\.uk/img/media/([^?]+)").find(u) ?: return null
        return "https://media.guim.co.uk/" + m.groupValues[1]
    }
}
