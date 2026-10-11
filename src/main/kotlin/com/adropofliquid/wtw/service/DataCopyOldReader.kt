package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.config.OldDatabase
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

// Rows of the OLD schema (inventory A2), as the data copy reads them. Old timestamps have no zone: read as UTC (brief 7.1).

data class OldProfile(
    val id: String, val vibes: String?, val event: String?, val style: String?, val displayName: String?,
    val timezone: String?, val createdAt: Instant?, val updatedAt: Instant?,
)

data class OldItem(
    val id: String, val userId: String?, val description: String?, val imageMetadataId: String?, val type: String?,
    val createdAt: Instant?, val updatedAt: Instant?,
    val formality: String?, val polish: String?, val fitShape: String?, val silhouetteRole: String?,
    val statementLevel: String?, val layeringRole: String?, val comfortCost: String?, val skinCoverageOverall: String?,
    /** jsonb columns, as JSON text. */
    val colorStory: String?, val fabricHand: String?, val skinCoverageZones: String?, val tags: String?,
) {
    val ownUpload: Boolean get() = imageMetadataId == id
}

data class OldImage(val id: String, val fileName: String?, val url: String?)

data class OldOutfit(
    val id: String, val userId: String?, val name: String?, val createdByAi: Boolean?, val occasion: String?,
    val outfitReview: String?, val createdAt: Instant?, val updatedAt: Instant?,
)

/** One `outfit_item` row. [seq] is its rank in physical (`ctid`) order over the whole table. */
data class OldOutfitItem(val seq: Int, val outfitId: String?, val itemId: String?, val position: DoubleArray?)

data class OldPlanned(
    val id: String, val userId: String?, val outfitId: String?, val plannedDate: LocalDateTime?, val createdAt: Instant?, val seq: Int,
)

data class OldToken(
    val id: String, val userId: String?, val fcmToken: String?, val platform: String?,
    val createdAt: Instant?, val updatedAt: Instant?, val lastNotifiedAt: Instant?, val seq: Int,
)

/** `suggestion_session` without `conversation_history` (29 MB; read per user, only when a row is written). */
data class OldSession(
    val id: String, val userId: String?, val startedAt: Instant?, val lastActivityAt: Instant?, val status: String?,
    val context: String?, val targetDate: LocalDate?, val originalEvent: String?, val originalVibes: String?,
    val originalMoreInfo: String?, val tooWarm: Boolean?, val tooCold: Boolean?, val tooCasual: Boolean?,
    val tooFormal: Boolean?, val differentTop: Boolean?, val differentBottom: Boolean?, val attemptNumber: Int?,
    val rejectedSuggestionIds: String?, val acceptedSuggestionId: String?, val createdAt: Instant?, val updatedAt: Instant?,
    val initiator: String?, val wardrobeSnapshotIds: String?,
)

data class OldSuggestion(
    val id: String, val userId: String?, val name: String?, val event: String?, val moreInfo: String?, val vibes: String?,
    val outfitReview: String?, val occasion: String?, val favorited: Boolean?, val createdAt: Instant?, val updatedAt: Instant?,
    val sessionId: String?, val attemptNumber: Int?, val wasRejected: Boolean?, val rejectionReason: String?, val feedbackText: String?,
)

/** Everything the copy needs from the old DB, loaded once (all tables are small; ~60 MB without transcripts). */
class OldData(
    val profiles: List<OldProfile>,
    val items: List<OldItem>,
    val images: List<OldImage>,
    val outfits: List<OldOutfit>,
    val outfitItems: List<OldOutfitItem>,
    val planned: List<OldPlanned>,
    val tokens: List<OldToken>,
    val sessions: List<OldSession>,
    val suggestions: List<OldSuggestion>,
)

/**
 * Reads the old database. The old schema has no entities in this app, so these plain SELECTs are the one
 * allowed exception to "no raw SQL" (brief 7; lead's instruction). SELECT only; the connections are read-only
 * (see OldDataSourceConfig).
 */
class DataCopyOldReader(private val old: OldDatabase) {

    fun loadAll(): OldData = OldData(
        profiles = old.jdbc.query(
            "SELECT id, vibes, event, style, display_name, timezone, created_at, updated_at FROM user_profile",
        ) { rs, _ ->
            OldProfile(rs.getString("id"), rs.getString("vibes"), rs.getString("event"), rs.getString("style"),
                rs.getString("display_name"), rs.getString("timezone"), rs.utc("created_at"), rs.utc("updated_at"))
        },
        items = old.jdbc.query(
            """SELECT id, user_id, description, image_metadata_id, type, created_at, updated_at, formality, polish, fit_shape,
                      silhouette_role, statement_level, layering_role, comfort_cost, skin_coverage_overall,
                      color_story::text AS color_story, fabric_hand::text AS fabric_hand,
                      skin_coverage_zones::text AS skin_coverage_zones, tags::text AS tags
               FROM clothing_item""",
        ) { rs, _ ->
            OldItem(rs.getString("id"), rs.getString("user_id"), rs.getString("description"), rs.getString("image_metadata_id"),
                rs.getString("type"), rs.utc("created_at"), rs.utc("updated_at"), rs.getString("formality"), rs.getString("polish"),
                rs.getString("fit_shape"), rs.getString("silhouette_role"), rs.getString("statement_level"),
                rs.getString("layering_role"), rs.getString("comfort_cost"), rs.getString("skin_coverage_overall"),
                rs.getString("color_story"), rs.getString("fabric_hand"), rs.getString("skin_coverage_zones"), rs.getString("tags"))
        },
        images = old.jdbc.query("SELECT id, file_name, url FROM image_metadata") { rs, _ ->
            OldImage(rs.getString("id"), rs.getString("file_name"), rs.getString("url"))
        },
        outfits = old.jdbc.query(
            "SELECT id, user_id, name, created_byai, occasion, outfit_review, created_at, updated_at FROM outfit",
        ) { rs, _ ->
            OldOutfit(rs.getString("id"), rs.getString("user_id"), rs.getString("name"), rs.bool("created_byai"),
                rs.getString("occasion"), rs.getString("outfit_review"), rs.utc("created_at"), rs.utc("updated_at"))
        },
        // Physical order matters: it is the stack order Flutter saved (brief 7.4) and the item order of a suggestion (7.6).
        outfitItems = old.jdbc.query("SELECT outfit_id, clothing_item_id, position FROM outfit_item ORDER BY ctid") { rs, n ->
            val pos = rs.getArray("position")?.let { arr -> (arr.array as Array<*>).map { (it as Number?)?.toDouble() ?: Double.NaN }.toDoubleArray() }
            OldOutfitItem(n, rs.getString("outfit_id"), rs.getString("clothing_item_id"), pos)
        },
        planned = old.jdbc.query(
            "SELECT id, user_id, outfit_id, planned_date, created_at FROM planned_outfit ORDER BY ctid",
        ) { rs, n ->
            OldPlanned(rs.getString("id"), rs.getString("user_id"), rs.getString("outfit_id"),
                rs.getObject("planned_date", LocalDateTime::class.java), rs.utc("created_at"), n)
        },
        tokens = old.jdbc.query(
            """SELECT id, user_id, fcm_token, platform, created_at, updated_at, last_notified_at
               FROM notification_token ORDER BY ctid""",
        ) { rs, n ->
            OldToken(rs.getString("id"), rs.getString("user_id"), rs.getString("fcm_token"), rs.getString("platform"),
                rs.utc("created_at"), rs.utc("updated_at"), rs.utc("last_notified_at"), n)
        },
        sessions = old.jdbc.query(
            """SELECT id, user_id, started_at, last_activity_at, status, context, target_date, original_event, original_vibes,
                      original_more_info, too_warm, too_cold, too_casual, too_formal, different_top, different_bottom,
                      attempt_number, rejected_suggestion_ids, accepted_suggestion_id, created_at, updated_at, initiator,
                      wardrobe_snapshot_ids
               FROM suggestion_session""",
        ) { rs, _ ->
            OldSession(rs.getString("id"), rs.getString("user_id"), rs.utc("started_at"), rs.utc("last_activity_at"),
                rs.getString("status"), rs.getString("context"), rs.getObject("target_date", LocalDate::class.java),
                rs.getString("original_event"), rs.getString("original_vibes"), rs.getString("original_more_info"),
                rs.bool("too_warm"), rs.bool("too_cold"), rs.bool("too_casual"), rs.bool("too_formal"),
                rs.bool("different_top"), rs.bool("different_bottom"), rs.int("attempt_number"),
                rs.getString("rejected_suggestion_ids"), rs.getString("accepted_suggestion_id"), rs.utc("created_at"),
                rs.utc("updated_at"), rs.getString("initiator"), rs.getString("wardrobe_snapshot_ids"))
        },
        suggestions = old.jdbc.query(
            """SELECT id, user_id, name, event, more_info, vibes, outfit_review, occasion, favorited, created_at, updated_at,
                      session_id, generated_at_attempt_number, was_rejected, rejection_reason, feedback_text
               FROM suggestion""",
        ) { rs, _ ->
            OldSuggestion(rs.getString("id"), rs.getString("user_id"), rs.getString("name"), rs.getString("event"),
                rs.getString("more_info"), rs.getString("vibes"), rs.getString("outfit_review"), rs.getString("occasion"),
                rs.bool("favorited"), rs.utc("created_at"), rs.utc("updated_at"), rs.getString("session_id"),
                rs.int("generated_at_attempt_number"), rs.bool("was_rejected"), rs.getString("rejection_reason"),
                rs.getString("feedback_text"))
        },
    )

    /** `conversation_history` of one user's sessions (id → text). */
    fun conversations(userId: String): Map<String, String?> {
        val out = HashMap<String, String?>()
        old.jdbc.query("SELECT id, conversation_history FROM suggestion_session WHERE user_id = ?", { rs ->
            out[rs.getString("id")] = rs.getString("conversation_history")
        }, userId)
        return out
    }

    private fun ResultSet.utc(col: String): Instant? = getObject(col, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)
    private fun ResultSet.bool(col: String): Boolean? = getBoolean(col).let { if (wasNull()) null else it }
    private fun ResultSet.int(col: String): Int? = getInt(col).let { if (wasNull()) null else it }
}
