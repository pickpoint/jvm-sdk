package io.pickpoint.tracking

import java.time.Duration

const val DEFAULT_TRACKING_ENDPOINT: String = "wss://tracking.pickpoint.io"
const val DEFAULT_WS_PATH: String = "/v2/tracking/ws"
const val SUBPROTOCOL: String = "tracking.v2.proto"
const val MAX_PUBLISH_HZ: Int = 50
val MIN_PUBLISH_INTERVAL: Duration = Duration.ofMillis(1000L / MAX_PUBLISH_HZ)
const val MAX_EVENT_BYTES: Int = 4 * 1024
const val MAX_EVENT_HZ: Int = 1
val MIN_EVENT_INTERVAL: Duration = Duration.ofSeconds(1)

enum class Transport {
    /** Binary protobuf on `/v2/tracking/ws` (default). */
    WS,
}

enum class ConnectionState {
    CONNECTING,
    OPEN,
    RECONNECTING,
    CLOSED,
}

data class DeviceAuth(
    val clientId: String,
    val clientSecret: String,
) {
    class Builder {
        private var clientId: String = ""
        private var clientSecret: String = ""
        fun clientId(v: String) = apply { clientId = v }
        fun clientSecret(v: String) = apply { clientSecret = v }
        fun build() = DeviceAuth(clientId, clientSecret)
    }

    companion object {
        @JvmStatic fun builder() = Builder()
    }
}

data class ListenerAuth(
    val accessToken: String,
) {
    class Builder {
        private var accessToken: String = ""
        fun accessToken(v: String) = apply { accessToken = v }
        fun build() = ListenerAuth(accessToken)
    }

    companion object {
        @JvmStatic fun builder() = Builder()
    }
}

fun interface RefreshAuth {
    fun refresh(): AuthRefresh
}

data class AuthRefresh(
    val device: DeviceAuth? = null,
    val listener: ListenerAuth? = null,
)

data class Config(
    val endpoint: String = DEFAULT_TRACKING_ENDPOINT,
    val transport: Transport = Transport.WS,
    val device: DeviceAuth? = null,
    val listener: ListenerAuth? = null,
    val wsPath: String = DEFAULT_WS_PATH,
    val disableReconnect: Boolean = false,
    val reconnectMinDelay: Duration = Duration.ofMillis(500),
    val reconnectMaxDelay: Duration = Duration.ofSeconds(30),
    val reconnectMaxAttempts: Int = 0,
    val refreshAuth: RefreshAuth? = null,
    val maxQueueSize: Int = 10_000,
    val helloTimeout: Duration = Duration.ofSeconds(10),
) {
    class Builder {
        private var endpoint: String = DEFAULT_TRACKING_ENDPOINT
        private var transport: Transport = Transport.WS
        private var device: DeviceAuth? = null
        private var listener: ListenerAuth? = null
        private var wsPath: String = DEFAULT_WS_PATH
        private var disableReconnect: Boolean = false
        private var reconnectMinDelay: Duration = Duration.ofMillis(500)
        private var reconnectMaxDelay: Duration = Duration.ofSeconds(30)
        private var reconnectMaxAttempts: Int = 0
        private var refreshAuth: RefreshAuth? = null
        private var maxQueueSize: Int = 10_000
        private var helloTimeout: Duration = Duration.ofSeconds(10)

        fun endpoint(v: String) = apply { endpoint = v }
        fun transport(v: Transport) = apply { transport = v }
        fun device(v: DeviceAuth?) = apply { device = v }
        fun listener(v: ListenerAuth?) = apply { listener = v }
        fun wsPath(v: String) = apply { wsPath = v }
        fun disableReconnect(v: Boolean) = apply { disableReconnect = v }
        fun reconnectMinDelay(v: Duration) = apply { reconnectMinDelay = v }
        fun reconnectMaxDelay(v: Duration) = apply { reconnectMaxDelay = v }
        fun reconnectMaxAttempts(v: Int) = apply { reconnectMaxAttempts = v }
        fun refreshAuth(v: RefreshAuth?) = apply { refreshAuth = v }
        fun maxQueueSize(v: Int) = apply { maxQueueSize = v }
        fun helloTimeout(v: Duration) = apply { helloTimeout = v }
        fun build() = Config(
            endpoint, transport, device, listener, wsPath, disableReconnect,
            reconnectMinDelay, reconnectMaxDelay, reconnectMaxAttempts,
            refreshAuth, maxQueueSize, helloTimeout,
        )
    }

    companion object {
        @JvmStatic fun builder() = Builder()
    }
}
