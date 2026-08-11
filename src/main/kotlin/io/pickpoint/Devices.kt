package io.pickpoint

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import java.util.Base64

@JsonIgnoreProperties(ignoreUnknown = true)
data class Device(
    val id: Long = 0,
    val uid: String = "",
    val name: String = "",
    val status: String = "",
    val description: String? = null,
    val tracksCount: Long = 0,
    val type: String = "",
    val secret: String = "",
    val metadata: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val lastLocation: JsonNode? = null,
)

data class DeviceInput(
    val name: String,
    val type: String,
    val description: String? = null,
    val metadata: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceListResult(
    val data: List<Device> = emptyList(),
    val total: Long = 0,
)

data class DeviceListQuery(
    val skip: Int = 0,
    val take: Int = 0,
    val search: String = "",
    val idle: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceCommandResult(
    val delivered: Long = 0,
)

/** Wraps `/v2/devices` endpoints. */
class DevicesService internal constructor(private val client: Client) {

    @JvmOverloads
    fun list(query: DeviceListQuery = DeviceListQuery()): DeviceListResult {
        val q = buildMap {
            if (query.skip > 0) put("skip", query.skip.toString())
            if (query.take > 0) put("take", query.take.toString())
            if (query.search.isNotEmpty()) put("search", query.search)
            if (query.idle) put("idle", "1")
        }
        val raw = client.doRequest(RequestOpts(path = "/v2/devices", query = q)) ?: ByteArray(0)
        return client.mapper.readValue(raw, DeviceListResult::class.java)
    }

    fun get(uid: String): Device {
        val raw = client.doRequest(
            RequestOpts(path = "/v2/devices/${pathEscape(uid)}"),
        ) ?: ByteArray(0)
        return client.mapper.readValue(raw, Device::class.java)
    }

    fun create(input: DeviceInput): Device {
        val raw = client.doRequest(
            RequestOpts(method = "POST", path = "/v2/devices", body = input),
        ) ?: ByteArray(0)
        return client.mapper.readValue(raw, Device::class.java)
    }

    fun update(uid: String, input: DeviceInput): Device {
        val raw = client.doRequest(
            RequestOpts(method = "PATCH", path = "/v2/devices/${pathEscape(uid)}", body = input),
        ) ?: ByteArray(0)
        return client.mapper.readValue(raw, Device::class.java)
    }

    fun delete(uid: String) {
        client.doRequest(RequestOpts(method = "DELETE", path = "/v2/devices/${pathEscape(uid)}"))
    }

    /** Injects opaque bytes into an online device session (base64-encoded by the SDK). */
    fun command(uid: String, payload: ByteArray): DeviceCommandResult {
        val raw = client.doRequest(
            RequestOpts(
                method = "POST",
                path = "/v2/devices/${pathEscape(uid)}/command",
                body = mapOf("payload" to Base64.getEncoder().encodeToString(payload)),
            ),
        ) ?: ByteArray(0)
        return client.mapper.readValue(raw, DeviceCommandResult::class.java)
    }
}

private fun pathEscape(s: String): String =
    java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
