package io.pickpoint

import com.fasterxml.jackson.databind.JsonNode

/** Valhalla proxies under `/v2/route`. */
class RoutingService internal constructor(private val client: Client) {

    private fun post(path: String, body: Any): JsonNode {
        val raw = client.doRequest(
            RequestOpts(method = "POST", path = path, body = body),
        ) ?: ByteArray(0)
        return client.mapper.readTree(raw)
    }

    fun route(body: Any): JsonNode = post("/v2/route", body)
    fun optimized(body: Any): JsonNode = post("/v2/route/optimized", body)
    fun matrix(body: Any): JsonNode = post("/v2/route/matrix", body)
    fun locate(body: Any): JsonNode = post("/v2/route/locate", body)
    fun elevation(body: Any): JsonNode = post("/v2/route/elevation", body)
}
