package com.alibaba.domain.model

data class ComboAccount(
    val username: String,
    val password: String
)

data class PanelInfo(
    val host: String,
    val port: Int = 8080,
    val isEmbedded: Boolean = false
) {
    val fullAddress: String
        get() = "$host:$port"
}

data class PanelScanResult(
    val account: ComboAccount,
    val panel: PanelInfo,
    val status: ScanStatus,
    val userInfo: UserInfo? = null,
    val serverInfo: ServerInfo? = null,
    val foundAt: Long = System.currentTimeMillis()
)

data class UserInfo(
    val username: String,
    val password: String,
    val status: String,
    val expDate: String?,
    val activeCons: Int,
    val maxConnections: Int,
    val isTrial: Boolean,
    val createdAt: String?
)

data class ServerInfo(
    val url: String,
    val port: String,
    val httpsPort: String?,
    val serverProtocol: String?,
    val rtmpPort: String?,
    val timezone: String?
)

sealed class ScanStatus {
    object Checking : ScanStatus()
    data class Valid(val channelCount: Int = 0) : ScanStatus()
    object Invalid : ScanStatus()
    data class Error(val message: String) : ScanStatus()
    object Banned : ScanStatus()
}

data class EmbeddedPanel(
    val host: String,
    val port: Int = 8080,
    val name: String = host
)

// Gömülü paneller - Python scriptindeki EMBEDDED_PANELS
object EmbeddedPanels {
    val panels = listOf(
        EmbeddedPanel("todserver.store", 8080, "TOD Server"),
        EmbeddedPanel("oygsnuh86f45.xyz", 8080, "Premium 1"),
        EmbeddedPanel("2025.snopytv57.co.uk", 8000, "Snopy TV"),
        EmbeddedPanel("ex.guteex.com", 8000, "Guteex"),
        EmbeddedPanel("protv65.shop", 8080, "Pro TV"),
        EmbeddedPanel("ch.bosstv.live", 8080, "Boss TV"),
        EmbeddedPanel("marsistv.xyz", 8080, "Marsis TV"),
        EmbeddedPanel("webtv5.xyz", 8080, "Web TV 5"),
        EmbeddedPanel("izmirblu.site", 2095, "Izmir Blu"),
        EmbeddedPanel("globaltv11.net", 8080, "Global TV"),
        EmbeddedPanel("frt.n-052.xyz", 8080, "FRT"),
        EmbeddedPanel("ses.vip7g.art", 8080, "VIP 7G"),
        EmbeddedPanel("p.hptdns.xyz", 8080, "HPT DNS"),
        EmbeddedPanel("ipzen.store", 8080, "IP Zen"),
        EmbeddedPanel("teos.stream-europa.com", 80, "Teos Stream"),
        EmbeddedPanel("bensteknik.com", 8080, "Ben Teknik"),
        EmbeddedPanel("maxtest.ddnsfree.com", 8080, "Max Test"),
        EmbeddedPanel("higherpro.xyz", 8080, "Higher Pro"),
        EmbeddedPanel("turksboxtv.com", 8080, "Turks Box TV"),
        EmbeddedPanel("tv.serverkomputerku.com", 8080, "Server Komputer"),
        EmbeddedPanel("stream.supertv24.com", 80, "Super TV 24"),
        EmbeddedPanel("portal.iptvglobal.org", 8080, "IPTV Global"),
        EmbeddedPanel("live.iptvking.net", 8000, "IPTV King"),
        EmbeddedPanel("server.xtremetv.org", 8080, "Xtreme TV"),
        EmbeddedPanel("tv.best-iptv.com", 80, "Best IPTV"),
        EmbeddedPanel("stream.premiumtv.live", 8080, "Premium TV Live"),
        EmbeddedPanel("portal.tvstreaming.com", 8000, "TV Streaming"),
        EmbeddedPanel("live.ultratv.pro", 8080, "Ultra TV Pro"),
        EmbeddedPanel("server.iptvmaster.net", 80, "IPTV Master"),
        EmbeddedPanel("iptv.goldserver.net", 8080, "Gold Server"),
        EmbeddedPanel("stream.tvworld.com", 8000, "TV World"),
        EmbeddedPanel("portal.viptv.stream", 80, "VIP TV Stream"),
        EmbeddedPanel("live.smartiptv.org", 8080, "Smart IPTV"),
        EmbeddedPanel("server.kingtv.net", 8000, "King TV"),
        EmbeddedPanel("tv.maxstreaming.com", 8080, "Max Streaming"),
        EmbeddedPanel("stream.elitetv.pro", 80, "Elite TV Pro"),
        EmbeddedPanel("portal.megatv.live", 8080, "Mega TV"),
        EmbeddedPanel("live.supremetv.net", 8000, "Supreme TV"),
        EmbeddedPanel("server.iptvpremium.org", 8080, "IPTV Premium")
    )
}
