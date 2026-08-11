package io.pickpoint

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Unified public-api client (geocoding, address, routing, devices).
 * Tracking (WebSocket) lives in [io.pickpoint.tracking] — different lifecycle.
 */
class Client internal constructor(
    internal val baseUrl: String,
    internal val http: OkHttpClient,
    internal val auth: AuthState,
    internal val maxRetries: Int,
    internal val retryBase: java.time.Duration,
    internal val concurrency: Int,
    internal val mapper: ObjectMapper,
) {
    val geocoding: GeocodingService = GeocodingService(this)
    val address: AddressService = AddressService(this)
    val routing: RoutingService = RoutingService(this)
    val devices: DevicesService = DevicesService(this)

    // Flat shortcuts (same idea as JS PickPoint / Go Client).
    fun forward(query: Map<String, String>) = geocoding.forward(query)
    fun reverse(query: Map<String, String>) = geocoding.reverse(query)
    fun lookup(query: Map<String, String>) = geocoding.lookup(query)
    fun forwardBatch(queries: List<Map<String, String>>) = geocoding.forwardBatch(queries)
    fun reverseBatch(queries: List<Map<String, String>>) = geocoding.reverseBatch(queries)
    fun search(query: Map<String, String>) = address.search(query)
    fun route(body: Any) = routing.route(body)
    fun optimizedRoute(body: Any) = routing.optimized(body)
    fun matrix(body: Any) = routing.matrix(body)
    fun locate(body: Any) = routing.locate(body)
    fun elevation(body: Any) = routing.elevation(body)

    companion object {
        @JvmStatic
        fun create(config: Config): Client {
            val base = (config.baseUrl.ifEmpty { DEFAULT_BASE_URL }).trimEnd('/')
            val timeout = if (config.timeout.isZero || config.timeout.isNegative) DEFAULT_TIMEOUT else config.timeout
            val http = config.httpClient ?: OkHttpClient.Builder()
                .callTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .build()

            var maxRetries = config.maxRetries
            if (maxRetries <= 0) maxRetries = DEFAULT_MAX_RETRIES

            var retryBase = config.retryBase
            if (retryBase.isZero || retryBase.isNegative) retryBase = DEFAULT_RETRY_BASE
            if (retryBase < MIN_RETRY_BASE) retryBase = MIN_RETRY_BASE

            var concurrency = config.concurrency
            if (concurrency <= 0) concurrency = DEFAULT_CONCURRENCY
            if (concurrency > MAX_CONCURRENCY) concurrency = MAX_CONCURRENCY

            val mapper = jacksonObjectMapper()
            val auth = resolveAuth(config, base, http, mapper)
            return Client(base, http, auth, maxRetries, retryBase, concurrency, mapper)
        }
    }
}

/** Kotlin-friendly constructor alias. */
fun Client(config: Config): Client = Client.create(config)
