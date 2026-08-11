package io.pickpoint

import com.fasterxml.jackson.databind.JsonNode

/** Photon search (`GET /v2/address/search`). */
class AddressService internal constructor(private val client: Client) {

    fun search(query: Map<String, String>): JsonNode {
        val raw = client.doRequest(
            RequestOpts(
                path = "/v2/address/search",
                query = query,
            ),
        ) ?: ByteArray(0)
        return client.mapper.readTree(raw)
    }
}
