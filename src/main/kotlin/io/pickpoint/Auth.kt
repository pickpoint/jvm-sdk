package io.pickpoint

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AuthKind { API_KEY, BEARER }

internal interface TokenSession {
    fun token(): String
    fun refreshAfterUnauthorized(): Boolean
}

internal class AuthState(
    val kind: AuthKind,
    val apiKey: String = "",
    val session: TokenSession? = null,
) {
    fun apply(request: Request.Builder) {
        request.header("Accept", "application/json")
        when (kind) {
            AuthKind.API_KEY -> request.header("x-api-key", apiKey)
            AuthKind.BEARER -> {
                val tok = session?.token()
                    ?: throw APIException(code = "INVALID_TOKEN", message = "missing bearer session")
                request.header("Authorization", "Bearer $tok")
            }
        }
    }
}

internal fun resolveAuth(cfg: Config, baseUrl: String, http: OkHttpClient, mapper: ObjectMapper): AuthState {
    var n = 0
    if (cfg.apiKey.isNotEmpty()) n++
    if (cfg.clientAuth != null) n++
    if (cfg.accessToken.isNotEmpty()) n++
    if (n > 1) {
        throw APIException(
            code = "INVALID_CONFIG",
            message = "provide only one of: apiKey | clientAuth | accessToken",
        )
    }
    if (n == 0) {
        throw APIException(
            code = "INVALID_CONFIG",
            message = "auth required: apiKey, clientAuth, or accessToken",
        )
    }
    if (cfg.apiKey.isNotEmpty()) {
        return AuthState(AuthKind.API_KEY, apiKey = cfg.apiKey)
    }
    if (cfg.clientAuth != null) {
        return AuthState(AuthKind.BEARER, session = ClientAuthSession(cfg.clientAuth, baseUrl, http, mapper))
    }
    return AuthState(AuthKind.BEARER, session = StaticSession(cfg.accessToken))
}

private class StaticSession(private val tok: String) : TokenSession {
    override fun token(): String = tok
    override fun refreshAfterUnauthorized(): Boolean = false
}

private class ClientAuthSession(
    initial: ClientAuth,
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val mapper: ObjectMapper,
) : TokenSession {
    private val lock = Any()
    private var accessToken: String
    private var refreshToken: String
    private var expiresAt: Long
    private var issuedAt: Instant
    private val refreshing = AtomicBoolean(false)
    private val waiters = CopyOnWriteArrayList<CountDownLatch>()
    @Volatile private var lastRefreshError: Exception? = null

    init {
        if (initial.accessToken.isEmpty() || initial.refreshToken.isEmpty() || initial.expiresAt == 0L) {
            throw APIException(
                code = "INVALID_CONFIG",
                message = "ClientAuth requires accessToken, refreshToken, and expiresAt (unix ms)",
            )
        }
        accessToken = initial.accessToken
        refreshToken = initial.refreshToken
        expiresAt = initial.expiresAt
        issuedAt = Instant.now()
    }

    override fun token(): String {
        if (needsProactiveRefresh()) {
            refresh()
        }
        synchronized(lock) {
            return accessToken
        }
    }

    override fun refreshAfterUnauthorized(): Boolean = try {
        refresh()
        true
    } catch (_: Exception) {
        false
    }

    private fun needsProactiveRefresh(): Boolean = synchronized(lock) {
        val ttlMs = expiresAt - issuedAt.toEpochMilli()
        if (ttlMs <= 0) {
            return Instant.now().toEpochMilli() >= expiresAt - 30_000
        }
        val refreshAtMs = issuedAt.toEpochMilli() + (ttlMs * CLIENT_AUTH_REFRESH_AT).toLong()
        return Instant.now().toEpochMilli() >= refreshAtMs
    }

    private fun refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            val latch = CountDownLatch(1)
            waiters.add(latch)
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw APIException(code = "REFRESH_FAILED", message = "client token refresh wait timed out")
            }
            lastRefreshError?.let { throw it }
            return
        }
        lastRefreshError = null
        val refreshTok: String
        synchronized(lock) { refreshTok = refreshToken }
        try {
            doRefresh(refreshTok)
        } catch (e: Exception) {
            lastRefreshError = e
            throw e
        } finally {
            refreshing.set(false)
            waiters.forEach { it.countDown() }
            waiters.clear()
        }
    }

    private fun doRefresh(refreshTok: String) {
        val bodyJson = mapper.writeValueAsString(mapOf("refreshToken" to refreshTok))
        val req = Request.Builder()
            .url("$baseUrl/v2/client-tokens/refresh")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()
        http.newCall(req).execute().use { res ->
            val raw = res.body?.bytes() ?: ByteArray(0)
            if (!res.isSuccessful) {
                throw APIException(
                    status = res.code,
                    code = "REFRESH_FAILED",
                    message = "client token refresh failed (${res.code})",
                    body = raw,
                )
            }
            val pair: ClientAuth = try {
                mapper.readValue(raw)
            } catch (e: Exception) {
                throw APIException(code = "INVALID_TOKEN", message = "refresh returned invalid JSON", cause = e)
            }
            if (pair.accessToken.isEmpty() || pair.refreshToken.isEmpty() || pair.expiresAt == 0L) {
                throw APIException(code = "INVALID_TOKEN", message = "refresh returned invalid clientAuth pair")
            }
            synchronized(lock) {
                accessToken = pair.accessToken
                refreshToken = pair.refreshToken
                expiresAt = pair.expiresAt
                issuedAt = Instant.now()
            }
        }
    }
}
