package io.pickpoint

import com.fasterxml.jackson.databind.JsonNode

/** Wraps `/v2/geocode/...` and `/v2/address/lookup`. */
class GeocodingService internal constructor(private val client: Client) {

    /** Forward geocode. On non-auth 4xx returns an empty list (batch-friendly). */
    fun forward(query: Map<String, String>): List<JsonNode> {
        val raw = client.doRequest(
            RequestOpts(
                path = "/v2/geocode/forward",
                query = query,
                onClientError = OnClientError.EMPTY,
                emptyBytes = "[]".toByteArray(),
            ),
        )
        return decodeJsonArray(client.mapper, raw)
    }

    /** Reverse geocode. On non-auth 4xx returns null. */
    fun reverse(query: Map<String, String>): JsonNode? {
        val raw = client.doRequest(
            RequestOpts(
                path = "/v2/geocode/reverse",
                query = query,
                onClientError = OnClientError.EMPTY,
                emptyBytes = "null".toByteArray(),
            ),
        )
        return decodeJsonObject(client.mapper, raw)
    }

    /** Resolve OSM ids (`GET /v2/address/lookup`). */
    fun lookup(query: Map<String, String>): List<JsonNode> {
        val raw = client.doRequest(
            RequestOpts(
                path = "/v2/address/lookup",
                query = query,
                onClientError = OnClientError.EMPTY,
                emptyBytes = "[]".toByteArray(),
            ),
        )
        return decodeJsonArray(client.mapper, raw)
    }

    fun forwardBatch(queries: List<Map<String, String>>): List<List<JsonNode>> {
        @Suppress("UNCHECKED_CAST")
        return runBatch(client.concurrency, queries) { forward(it) } as List<List<JsonNode>>
    }

    fun reverseBatch(queries: List<Map<String, String>>): List<JsonNode?> {
        @Suppress("UNCHECKED_CAST")
        return runBatch(client.concurrency, queries) { reverse(it) } as List<JsonNode?>
    }

    fun lookupBatch(queries: List<Map<String, String>>): List<List<JsonNode>> {
        @Suppress("UNCHECKED_CAST")
        return runBatch(client.concurrency, queries) { lookup(it) } as List<List<JsonNode>>
    }
}
