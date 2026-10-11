package com.adropofliquid.wtw.service

import org.springframework.http.HttpStatus

/**
 * The one error type services throw. Rendered by ApiExceptionHandler as
 * `{"error": "<message>", "code": "<CODE>", ...extra}` (brief 4.0).
 */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
    val extra: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    companion object {
        fun validation(msg: String) = ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", msg)
        fun notFound(msg: String = "Not found") = ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", msg)
        fun conflict(code: String, msg: String) = ApiException(HttpStatus.CONFLICT, code, msg)
        fun tooLarge(msg: String) = ApiException(HttpStatus.CONTENT_TOO_LARGE, "TOO_LARGE", msg)
        fun insufficientCredits(balance: Int, cost: Int) = ApiException(
            HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_CREDITS", "Not enough credits",
            mapOf("balance" to balance, "cost" to cost),
        )
        fun limitReached(which: String) = ApiException(
            HttpStatus.TOO_MANY_REQUESTS, "LIMIT_REACHED", "Daily limit reached", mapOf("limit" to which),
        )
        fun upstreamFailed(upstream: String, cause: Throwable? = null) =
            ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_FAILED", "$upstream failed", cause = cause)
        fun upstreamTimeout(upstream: String, cause: Throwable? = null) =
            ApiException(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", "$upstream timed out", cause = cause)
    }
}
