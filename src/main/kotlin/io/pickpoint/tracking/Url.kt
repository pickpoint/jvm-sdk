package io.pickpoint.tracking

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Builds an OkHttp-compatible WebSocket URL (`http`/`https`).
 * Accepts `ws`/`wss`/`http`/`https` or bare `host:port` in [Config.endpoint].
 */
fun buildWsUrl(config: Config): HttpUrl {
    var raw = config.endpoint.trim()
    if (raw.isEmpty()) throw TrackingException(message = "Endpoint is required")
    if (!raw.contains("://")) {
        raw = "ws://$raw"
    }

    val scheme = raw.substringBefore("://").lowercase()
    val rest = raw.substringAfter("://")
    val httpScheme = when (scheme) {
        "ws", "http" -> "http"
        "wss", "https" -> "https"
        else -> throw TrackingException(message = "unsupported scheme $scheme")
    }

    val parsed = "$httpScheme://$rest".toHttpUrlOrNull()
        ?: throw TrackingException(message = "bad endpoint: ${config.endpoint}")

    val path = config.wsPath.ifEmpty { DEFAULT_WS_PATH }
    val builder = parsed.newBuilder().encodedPath(path).query(null)
    when {
        config.device != null -> {
            builder.addQueryParameter("client-id", config.device.clientId)
            builder.addQueryParameter("client-secret", config.device.clientSecret)
        }
        config.listener != null -> {
            builder.addQueryParameter("access-token", config.listener.accessToken)
        }
    }
    return builder.build()
}
