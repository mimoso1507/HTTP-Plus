package com.httpplus.app

/** Bloqueo simple por dominio (el dominio y sus subdominios). Las listas son cortas, no completas. */
class Filter(private val cfg: Config) {
    private val ads = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "adnxs.com", "adsrvr.org", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
        "scorecardresearch.com", "moatads.com", "pubmatic.com", "rubiconproject.com", "openx.net",
        "amazon-adsystem.com", "adcolony.com", "unityads.unity3d.com", "applovin.com", "mopub.com",
        "inmobi.com", "vungle.com", "chartboost.com", "smaato.net", "ads.yahoo.com", "advertising.com",
        "popads.net", "propellerads.com", "exoclick.com", "juicyads.com", "trafficjunky.net"
    )
    private val adult = setOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com", "youporn.com",
        "spankbang.com", "eporner.com", "tube8.com", "brazzers.com", "onlyfans.com", "chaturbate.com",
        "stripchat.com", "livejasmin.com", "rule34.xxx", "hentaihaven.xxx", "nhentai.net"
    )
    private val custom = cfg.customBlock.split(',', ' ', '\n', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    private fun matches(host: String, set: Set<String>): Boolean {
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in set) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    fun blocked(host: String): Boolean =
        (cfg.blockAds && matches(host, ads)) || (cfg.blockAdult && matches(host, adult)) || matches(host, custom)
}
