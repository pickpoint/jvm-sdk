package io.pickpoint.tracking

import io.pickpoint.tracking.v2.ErrorCode
import io.pickpoint.tracking.v2.Error as WireError

open class TrackingException(
    val code: ErrorCode = ErrorCode.ERROR_CODE_INVALID,
    message: String = "",
    cause: Throwable? = null,
) : RuntimeException(
    if (message.isNotEmpty()) "tracking: $message" else "tracking: ${code.name}",
    cause,
)

fun isAuthError(code: ErrorCode): Boolean =
    code == ErrorCode.ERROR_CODE_AUTH || code == ErrorCode.ERROR_CODE_UNAUTHORIZED

fun isFatalResumeError(code: ErrorCode): Boolean = when (code) {
    ErrorCode.ERROR_CODE_TRACK_NOT_FOUND,
    ErrorCode.ERROR_CODE_FENCED,
    ErrorCode.ERROR_CODE_AUTH,
    ErrorCode.ERROR_CODE_UNAUTHORIZED,
    -> true
    else -> false
}

fun errorFromWire(err: WireError): TrackingException =
    TrackingException(err.code, err.message.ifEmpty { err.code.name })
