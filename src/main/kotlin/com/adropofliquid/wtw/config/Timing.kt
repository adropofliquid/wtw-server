package com.adropofliquid.wtw.config

import org.slf4j.Logger

/**
 * Times [block] and logs one INFO line, `<event> ms=<n> <fields>`, where [fields] describes the result
 * (`"rows=582 bytes=812345"`). A failure logs `<event> failed ms=<n> err=<type>` at WARN and is rethrown.
 * Every line also carries the request id and uid hash (log pattern, see RequestLoggingFilter).
 */
inline fun <T> Logger.timed(event: String, fields: (T) -> String = { "" }, block: () -> T): T {
    val start = System.nanoTime()
    val result = try {
        block()
    } catch (e: Throwable) {
        warn("{} failed ms={} err={}", event, msSince(start), e.javaClass.simpleName)
        throw e
    }
    info("{} ms={} {}", event, msSince(start), fields(result))
    return result
}

fun msSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
