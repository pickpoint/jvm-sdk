package io.pickpoint.tracking

open class TrackingException(
    val code: ErrorCode = ErrorCode.INVALID,
    message: String = "",
    cause: Throwable? = null,
) : RuntimeException(
    if (message.isNotEmpty()) "tracking: $message" else "tracking: ${code.name}",
    cause,
)

fun isAuthError(code: ErrorCode): Boolean =
    code == ErrorCode.AUTH || code == ErrorCode.UNAUTHORIZED

fun isFatalResumeError(code: ErrorCode): Boolean =
    code == ErrorCode.TRACK_NOT_FOUND || code == ErrorCode.AUTH

fun isRetryResumeError(code: ErrorCode): Boolean =
    code == ErrorCode.FENCED || code == ErrorCode.TRY_AGAIN

fun errorFromWire(err: WireError): TrackingException =
    TrackingException(err.code, err.message.ifEmpty { err.code.name })
