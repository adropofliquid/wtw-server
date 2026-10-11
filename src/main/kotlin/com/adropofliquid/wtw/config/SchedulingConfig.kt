package com.adropofliquid.wtw.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** Scheduled jobs (push check, session sweep) run only in the web app, never inside a one-off command container. */
@Configuration
@ConditionalOnWebApplication
@EnableScheduling
class SchedulingConfig
