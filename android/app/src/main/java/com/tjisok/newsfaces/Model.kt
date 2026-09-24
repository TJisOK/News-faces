package com.tjisok.newsfaces

data class Feed(val id: String, val pub: String, val name: String, val sec: String, val url: String)

data class PhotoColor(
    val hue: Float, val sat: Float, val light: Float,
    val avg: Int, val vivid: Int, val fail: Boolean = false
)

data class Photo(
    val id: String, val img: String, val link: String, val title: String,
    val time: Long, val source: String, val sec: String, val sid: String,
    val color: PhotoColor? = null, val lowres: Boolean = false
)

enum class FeedState { IDLE, LOADING, OK, FAIL }

object Feeds {
    val ALL: List<Feed> = buildList {
        fun add(pub: String, name: String, list: List<Pair<String, String>>) {
            for ((sec, url) in list) add(Feed(pub + "-" + sec.lowercase().replace(Regex("[^a-z0-9]+"), "-"), pub, name, sec, url))
        }
        val g = { s: String -> "https://www.theguardian.com/$s/rss" }
        val b = { s: String -> "https://feeds.bbci.co.uk/news/$s/rss.xml" }
        val n = { s: String -> "https://rss.nytimes.com/services/xml/rss/nyt/$s.xml" }
        val c = { s: String -> "https://www.cbc.ca/webfeed/rss/rss-$s" }
        val f = { s: String -> "https://www.france24.com/en/${s}rss" }
        val nb = { s: String -> "https://feeds.nbcnews.com/nbcnews/public/$s" }
        add("guardian", "The Guardian", listOf("World" to g("world"), "UK" to g("uk-news"), "US" to g("us-news"), "Environment" to g("environment"), "Science" to g("science"), "Technology" to g("technology"), "Business" to g("business"), "Sport" to g("sport"), "Football" to g("football"), "Culture" to g("culture"), "Film" to g("film"), "Music" to g("music"), "Art & design" to g("artanddesign"), "Fashion" to g("fashion"), "Food" to g("food"), "Travel" to g("travel"), "Global development" to g("global-development")))
        add("bbc", "BBC News", listOf("World" to b("world"), "UK" to b("uk"), "Business" to b("business"), "Politics" to b("politics"), "Health" to b("health"), "Science & Environment" to b("science_and_environment"), "Technology" to b("technology"), "Entertainment & Arts" to b("entertainment_and_arts"), "Asia" to b("world/asia"), "Europe" to b("world/europe"), "US & Canada" to b("world/us_and_canada"), "Africa" to b("world/africa"), "Middle East" to b("world/middle_east"), "Sport" to "https://feeds.bbci.co.uk/sport/rss.xml"))
        add("nyt", "New York Times", listOf("World" to n("World"), "US" to n("US"), "Business" to n("Business"), "Technology" to n("Technology"), "Science" to n("Science"), "Climate" to n("Climate"), "Arts" to n("Arts"), "Music" to n("Music"), "Fashion & Style" to n("FashionandStyle"), "Food" to n("DiningandWine"), "Middle East" to n("MiddleEast"), "Americas" to n("Americas")))
        add("cbc", "CBC", listOf("Top stories" to c("topstories"), "World" to c("world"), "Canada" to c("canada"), "Politics" to c("politics"), "Business" to c("business"), "Arts" to c("arts"), "Sports" to c("sports"), "Indigenous" to c("Indigenous")))
        add("france24", "France 24", listOf("Top stories" to f(""), "Europe" to f("europe/"), "Africa" to f("africa/"), "Americas" to f("americas/"), "Asia-Pacific" to f("asia-pacific/"), "Middle East" to f("middle-east/"), "Culture" to f("culture/"), "Sport" to f("sport/")))
        add("nbc", "NBC News", listOf("World" to nb("world"), "US news" to nb("us-news"), "Politics" to nb("politics"), "Business" to nb("business"), "Tech" to nb("tech"), "Science" to nb("science"), "Pop culture" to nb("pop-culture")))
        add("abc", "ABC Australia", listOf("Top stories" to "https://www.abc.net.au/news/feed/2942460/rss.xml", "Just in" to "https://www.abc.net.au/news/feed/51120/rss.xml", "Business" to "https://www.abc.net.au/news/feed/51892/rss.xml", "Sport" to "https://www.abc.net.au/news/feed/45910/rss.xml"))
    }
    val byId: Map<String, Feed> = ALL.associateBy { it.id }
}
