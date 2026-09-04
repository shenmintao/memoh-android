package icu.minq.memoh.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrl {
    fun normalize(input: String): String {
        var raw = input.trim()
        require(raw.isNotEmpty()) { "请输入服务器地址" }
        if (!raw.contains("://")) raw = "https://$raw"
        val parsed = raw.trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("服务器地址无效")
        require(parsed.scheme == "https") { "仅支持 HTTPS 服务器" }
        val segments = parsed.pathSegments.filter { it.isNotBlank() }.toMutableList()
        if (segments.lastOrNull() != "api") segments += "api"
        return parsed.newBuilder().encodedPath("/" + segments.joinToString("/")).query(null).fragment(null).build().toString().trimEnd('/')
    }

    fun webSocket(apiBase: String, botId: String): String {
        val http = normalize(apiBase).toHttpUrlOrNull() ?: error("invalid api base")
        return http.newBuilder().addPathSegments("bots/$botId/web/ws").build().toString().replaceFirst("https://", "wss://")
    }
}
