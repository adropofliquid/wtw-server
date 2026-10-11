package com.adropofliquid.wtw

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class WtwApplication

/** One-off commands that run inside the same jar and exit (no web server): see README "Commands". */
val ONE_OFF_COMMANDS = listOf("--seed-library", "--data-copy")

fun main(args: Array<String>) {
    val oneOff = args.any { a -> ONE_OFF_COMMANDS.any { a.startsWith(it) } }
    runApplication<WtwApplication>(*args) {
        if (oneOff) setDefaultProperties(mapOf("spring.main.web-application-type" to "none"))
    }
}
