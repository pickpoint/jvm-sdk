package io.pickpoint

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenPair(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val expiresIn: Long = 0,
    val scopes: List<String> = emptyList(),
)

/**
 * Mints a client-token pair with a secret API key (server-side).
 * Pass empty [scopes] to grant all client-tokenable permissions on the key.
 */
@JvmOverloads
fun mintClientTokens(
    config: Config,
    scopes: List<String> = emptyList(),
    ttlSec: Long = 0,
): TokenPair {
    if (config.apiKey.isEmpty()) {
        throw APIException(code = "INVALID_CONFIG", message = "mintClientTokens requires apiKey")
    }
    val base = (config.baseUrl.ifEmpty { DEFAULT_BASE_URL }).trimEnd('/')
    val timeout = if (config.timeout.isZero || config.timeout.isNegative) DEFAULT_TIMEOUT else config.timeout
    val http = config.httpClient ?: OkHttpClient.Builder()
        .callTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .build()
    val mapper = jacksonObjectMapper()
    val payload = mutableMapOf<String, Any>("scopes" to scopes)
    if (ttlSec > 0) payload["ttlSec"] = ttlSec
    val body = mapper.writeValueAsBytes(payload)
    val req = Request.Builder()
        .url("$base/v2/client-tokens")
        .post(body.toRequestBody("application/json".toMediaType()))
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("x-api-key", config.apiKey)
        .build()
    http.newCall(req).execute().use { res ->
        val raw = res.body?.bytes() ?: ByteArray(0)
        if (!res.isSuccessful) {
            throw APIException(
                status = res.code,
                code = "CLIENT_ERROR",
                message = "mint client tokens failed (${res.code})",
                body = raw,
            )
        }
        return mapper.readValue(raw, TokenPair::class.java)
    }
}
