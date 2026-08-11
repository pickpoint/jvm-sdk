package io.pickpoint

/** Base SDK error. */
open class PickpointException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Auth failed (401 / 402 / 403 / refresh failed). */
class AuthException(message: String = "pickpoint: auth failed", cause: Throwable? = null) :
    PickpointException(message, cause)

/** Resource not found (404). */
class NotFoundException(message: String = "pickpoint: not found", cause: Throwable? = null) :
    PickpointException(message, cause)

/** Conflict (409). */
class ConflictException(message: String = "pickpoint: conflict", cause: Throwable? = null) :
    PickpointException(message, cause)

/** Invalid client configuration. */
class InvalidConfigException(message: String = "pickpoint: invalid config", cause: Throwable? = null) :
    PickpointException(message, cause)

/** Non-2xx public-api response (or transport failure after retries). */
class APIException(
    val status: Int = 0,
    val code: String = "",
    override val message: String = "",
    val body: ByteArray = ByteArray(0),
    cause: Throwable? = null,
) : PickpointException(
    buildString {
        append("pickpoint: ")
        append(if (message.isNotEmpty()) message else "request failed")
        append(" (status=")
        append(status)
        append(" code=")
        append(code)
        append(")")
    },
    cause,
) {
    fun isAuth(): Boolean = code == "API_AUTH" || code == "REFRESH_FAILED"
    fun isNotFound(): Boolean = code == "NOT_FOUND"
    fun isConflict(): Boolean = code == "CONFLICT"
    fun isInvalidConfig(): Boolean = code == "INVALID_CONFIG"

    fun asTyped(): PickpointException = when (code) {
        "API_AUTH", "REFRESH_FAILED" -> AuthException(this.message, this)
        "NOT_FOUND" -> NotFoundException(this.message, this)
        "CONFLICT" -> ConflictException(this.message, this)
        "INVALID_CONFIG" -> InvalidConfigException(this.message, this)
        else -> this
    }
}
