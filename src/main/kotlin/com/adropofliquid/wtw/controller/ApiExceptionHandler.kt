package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.service.ApiException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.resource.NoResourceFoundException

/** Every error leaves as `{"error": "...", "code": "..."}` (brief 4.0). */
@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<Map<String, Any?>> {
        if (e.status.is5xxServerError) log.warn("{} {}: {}", e.status.value(), e.code, e.message, e.cause)
        return body(e.status, e.code, e.message ?: e.code, e.extra)
    }

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MissingServletRequestPartException::class,
        MethodArgumentTypeMismatchException::class,
        HttpMediaTypeNotSupportedException::class,
        MultipartException::class,
    )
    fun badRequest(e: Exception): ResponseEntity<Map<String, Any?>> {
        if (e is MaxUploadSizeExceededException) return body(HttpStatus.CONTENT_TOO_LARGE, "TOO_LARGE", "Upload too large")
        return body(HttpStatus.BAD_REQUEST, "VALIDATION", e.message?.substringBefore(":")?.take(200) ?: "Bad request")
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooLarge(e: MaxUploadSizeExceededException) = body(HttpStatus.CONTENT_TOO_LARGE, "TOO_LARGE", "Upload too large")

    @ExceptionHandler(NoResourceFoundException::class)
    fun noRoute(e: NoResourceFoundException) = body(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such route")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(e: HttpRequestMethodNotSupportedException) = body(HttpStatus.METHOD_NOT_ALLOWED, "VALIDATION", "Method not allowed")

    @ExceptionHandler(Exception::class)
    fun other(e: Exception): ResponseEntity<Map<String, Any?>> {
        log.error("Unhandled error", e)
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Something went wrong")
    }

    private fun body(status: HttpStatus, code: String, message: String, extra: Map<String, Any?> = emptyMap()) =
        ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .body(linkedMapOf<String, Any?>("error" to message, "code" to code) + extra)
}
