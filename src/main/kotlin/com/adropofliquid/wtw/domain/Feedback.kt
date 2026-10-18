package com.adropofliquid.wtw.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** In-app feedback. Written only by FeedbackRepository.insertIfAbsent; read in SQL. */
@Entity
@Table(name = "feedback")
class Feedback(
    @Id var id: UUID = UUID.randomUUID(),
    /** From the auth token. */
    var userId: String = "",
    /** The body's uid, kept for the mismatch check. */
    var bodyUid: String = "",
    var message: String = "",
    /** idea, problem, other, or null. */
    var category: String? = null,
    var canReply: Boolean = false,
    var email: String? = null,
    var appVersion: String = "",
    var platform: String = "",
    var osVersion: String = "",
    /** Phone time when the user tapped Send. */
    var createdAt: Instant = Instant.now(),
    var receivedAt: Instant = Instant.now(),
    /** new, read, replied, done: the developer's own triage. */
    var status: String = "new",
)
