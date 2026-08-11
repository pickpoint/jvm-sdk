package io.pickpoint

import okhttp3.OkHttpClient
import java.time.Duration

const val DEFAULT_BASE_URL: String = "https://api.pickpoint.io"
const val DEFAULT_MAX_RETRIES: Int = 3
val DEFAULT_RETRY_BASE: Duration = Duration.ofSeconds(1)
val MIN_RETRY_BASE: Duration = Duration.ofMillis(200)
val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)
const val MAX_CONCURRENCY: Int = 20
const val DEFAULT_CONCURRENCY: Int = 20
internal const val CLIENT_AUTH_REFRESH_AT: Double = 0.5

/** Short-lived SPA pair from `POST /v2/client-tokens`. [expiresAt] is unix epoch milliseconds. */
data class ClientAuth(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
) {
    class Builder {
        private var accessToken: String = ""
        private var refreshToken: String = ""
        private var expiresAt: Long = 0

        fun accessToken(value: String) = apply { accessToken = value }
        fun refreshToken(value: String) = apply { refreshToken = value }
        fun expiresAt(value: Long) = apply { expiresAt = value }
        fun build() = ClientAuth(accessToken, refreshToken, expiresAt)
    }

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }
}

/**
 * Configures the public-api client.
 * Provide exactly one of [apiKey], [clientAuth], or [accessToken].
 */
data class Config(
    val apiKey: String = "",
    val clientAuth: ClientAuth? = null,
    val accessToken: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val httpClient: OkHttpClient? = null,
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val retryBase: Duration = DEFAULT_RETRY_BASE,
    val timeout: Duration = DEFAULT_TIMEOUT,
    val concurrency: Int = DEFAULT_CONCURRENCY,
) {
    class Builder {
        private var apiKey: String = ""
        private var clientAuth: ClientAuth? = null
        private var accessToken: String = ""
        private var baseUrl: String = DEFAULT_BASE_URL
        private var httpClient: OkHttpClient? = null
        private var maxRetries: Int = DEFAULT_MAX_RETRIES
        private var retryBase: Duration = DEFAULT_RETRY_BASE
        private var timeout: Duration = DEFAULT_TIMEOUT
        private var concurrency: Int = DEFAULT_CONCURRENCY

        fun apiKey(value: String) = apply { apiKey = value }
        fun clientAuth(value: ClientAuth?) = apply { clientAuth = value }
        fun accessToken(value: String) = apply { accessToken = value }
        fun baseUrl(value: String) = apply { baseUrl = value }
        fun httpClient(value: OkHttpClient?) = apply { httpClient = value }
        fun maxRetries(value: Int) = apply { maxRetries = value }
        fun retryBase(value: Duration) = apply { retryBase = value }
        fun timeout(value: Duration) = apply { timeout = value }
        fun concurrency(value: Int) = apply { concurrency = value }
        fun build() = Config(
            apiKey = apiKey,
            clientAuth = clientAuth,
            accessToken = accessToken,
            baseUrl = baseUrl,
            httpClient = httpClient,
            maxRetries = maxRetries,
            retryBase = retryBase,
            timeout = timeout,
            concurrency = concurrency,
        )
    }

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }
}
