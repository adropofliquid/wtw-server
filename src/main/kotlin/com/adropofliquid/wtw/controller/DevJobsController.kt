package com.adropofliquid.wtw.controller

import com.adropofliquid.wtw.service.NotificationJobService
import com.adropofliquid.wtw.service.PushCheckReport
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Not prod: runs the scheduled jobs (brief 5.5) on demand, for local checks. Not in the API.
 * `at` (epoch ms) pretends the clock reads that time, so 07:00 and Sunday 19:00 can be tried any time.
 */
@RestController
@Profile("!prod")
@RequestMapping("/dev/jobs")
class DevJobsController(private val jobs: NotificationJobService) {
    @PostMapping("/push-check")
    fun pushCheck(@RequestParam(required = false) at: Long?): PushCheckReport =
        jobs.pushCheck(at?.let(Instant::ofEpochMilli) ?: Instant.now())

    @PostMapping("/session-sweep")
    fun sessionSweep(@RequestParam(required = false) at: Long?): Map<String, Int> =
        mapOf("abandoned" to jobs.sweepSessions(at?.let(Instant::ofEpochMilli) ?: Instant.now()))
}
