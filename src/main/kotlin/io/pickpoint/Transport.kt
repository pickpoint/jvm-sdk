package io.pickpoint

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration
import kotlin.math.min
import kotlin.random.Random

internal enum class OnClientError { THROW, EMPTY }

internal data class RequestOpts(
    val method: String? = null,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: Any? = null,
    val onClientError: OnClientError = OnClientError.THROW,
    val emptyBytes: ByteArray? = null,
)

internal fun Client.doRequest(opts: RequestOpts): ByteArray? {
    var attempt = 0
    var authRetried = false

    while (true) {
        var urlBuilder = (baseUrl + opts.path).toHttpUrl().newBuilder()
        for ((k, v) in opts.query) {
            if (v.isNotEmpty()) urlBuilder = urlBuilder.addQueryParameter(k, v)
        }
        val url = urlBuilder.build()

        val method = opts.method
            ?: if (opts.body != null) "POST" else "GET"
        val reqBuilder = Request.Builder().url(url)
        if (opts.body != null) {
            val json = mapper.writeValueAsBytes(opts.body)
            reqBuilder.method(method, json.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
        } else {
            reqBuilder.method(method, null)
        }
        auth.apply(reqBuilder)

        var retry = false
        val result: ByteArray? = try {
            http.newCall(reqBuilder.build()).execute().use { res ->
            val raw = try {
                res.body?.bytes() ?: ByteArray(0)
            } catch (e: Exception) {
                if (attempt >= maxRetries) {
                    throw APIException(code = "NETWORK", message = "network error", cause = e)
                }
                sleepBackoff(retryBase, attempt)
                attempt++
                retry = true
                return@use null
            }
            val code = res.code

            when {
                code == 401 -> {
                    if (!authRetried && auth.kind == AuthKind.BEARER &&
                        auth.session?.refreshAfterUnauthorized() == true
                    ) {
                        authRetried = true
                        retry = true
                        null
                    } else {
                        throw APIException(
                            status = code,
                            code = "API_AUTH",
                            message = "auth failed (401)",
                            body = raw,
                        )
                    }
                }

                code == 402 || code == 403 ->
                    throw APIException(status = code, code = "API_AUTH", message = "auth failed", body = raw)

                code == 204 -> null

                code == 409 ->
                    throw APIException(
                        status = 409,
                        code = "CONFLICT",
                        message = messageFromBody(mapper, raw, 409),
                        body = raw,
                    )

                code == 400 || (code in 404..499) -> {
                    if (opts.onClientError == OnClientError.EMPTY) {
                        opts.emptyBytes
                    } else {
                        val errCode = if (code == 404) "NOT_FOUND" else "CLIENT_ERROR"
                        throw APIException(
                            status = code,
                            code = errCode,
                            message = messageFromBody(mapper, raw, code),
                            body = raw,
                        )
                    }
                }

                code >= 500 -> {
                    if (attempt >= maxRetries) {
                        throw APIException(
                            status = code,
                            code = "SERVER_ERROR",
                            message = "server error after retries",
                            body = raw,
                        )
                    }
                    sleepBackoff(retryBase, attempt)
                    attempt++
                    retry = true
                    null
                }

                code in 200..299 -> raw

                else -> {
                    if (code in 400..499 && opts.onClientError == OnClientError.EMPTY) {
                        opts.emptyBytes
                    } else {
                        throw APIException(
                            status = code,
                            code = "CLIENT_ERROR",
                            message = messageFromBody(mapper, raw, code),
                            body = raw,
                        )
                    }
                }
            }
            }
        } catch (e: APIException) {
            throw e
        } catch (e: Exception) {
            if (attempt >= maxRetries) {
                throw APIException(code = "NETWORK", message = "network error", cause = e)
            }
            sleepBackoff(retryBase, attempt)
            attempt++
            continue
        }

        if (retry) continue
        return result
    }
}

internal fun messageFromBody(mapper: ObjectMapper, raw: ByteArray, status: Int): String {
    if (raw.isEmpty()) return statusText(status)
    return try {
        val node = mapper.readTree(raw)
        textField(node, "message") ?: textField(node, "error") ?: statusText(status)
    } catch (_: Exception) {
        statusText(status)
    }
}

private fun textField(node: JsonNode, name: String): String? {
    val n = node.get(name) ?: return null
    return if (n.isTextual) n.asText().takeIf { it.isNotEmpty() } else null
}

private fun statusText(status: Int): String = when (status) {
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    409 -> "Conflict"
    else -> "HTTP $status"
}

internal fun sleepBackoff(base: Duration, attempt: Int) {
    val b = if (base.isZero || base.isNegative) DEFAULT_RETRY_BASE else base
    val maxMs = b.toMillis() shl attempt
    val d = Random.nextLong(0, maxMs + 1)
    Thread.sleep(d)
}

internal fun decodeJsonArray(mapper: ObjectMapper, raw: ByteArray?): List<JsonNode> {
    if (raw == null || raw.isEmpty()) return emptyList()
    val node = mapper.readTree(raw)
    return when {
        node.isArray -> node.toList()
        node.isNull -> emptyList()
        else -> listOf(node)
    }
}

internal fun decodeJsonObject(mapper: ObjectMapper, raw: ByteArray?): JsonNode? {
    if (raw == null || raw.isEmpty() || raw.contentEquals("null".toByteArray())) return null
    return mapper.readTree(raw)
}

internal fun <T> runBatch(
    concurrency: Int,
    inputs: List<T>,
    fn: (T) -> Any?,
): List<Any?> {
    if (inputs.isEmpty()) return emptyList()
    val workers = min(concurrency.coerceAtLeast(1), inputs.size)
    val results = arrayOfNulls<Any?>(inputs.size)
    var firstError: Exception? = null
    val lock = Any()
    var nextIndex = 0
    val threads = (0 until workers).map {
        Thread {
            while (true) {
                val i: Int
                synchronized(lock) {
                    if (firstError != null || nextIndex >= inputs.size) return@Thread
                    i = nextIndex++
                }
                try {
                    val v = fn(inputs[i])
                    synchronized(lock) { results[i] = v }
                } catch (e: Exception) {
                    synchronized(lock) {
                        if (firstError == null) firstError = e
                    }
                    return@Thread
                }
            }
        }.also { it.start() }
    }
    threads.forEach { it.join() }
    firstError?.let { throw it }
    return results.toList()
}
