package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.config.RequestLoggingFilter.Companion.uidHash
import com.adropofliquid.wtw.domain.RevenueCatEvent
import com.adropofliquid.wtw.repository.RevenueCatEventRepository
import com.adropofliquid.wtw.repository.UserProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * RevenueCat webhook handling (brief 4.6, 5.7).
 * 1. Store the event in `revenuecat_event`. An event already processed (same id, processed_at set) stops here.
 *    An event stored but not processed (an earlier try failed half-way) is processed again; the ledger refs
 *    (`transaction_id`, `refund:{transaction_id}`) make that safe.
 * 2. Act on the type (table in 4.6), evict the entitlement cache, set outcome + processed_at.
 *    Exception: a pack purchase whose product id is not in `app_config.ledger.packs` is stored with outcome
 *    `unmatched_product` and NO processed_at, logged at WARN. A later delivery of the same event (RevenueCat
 *    retry or a "resend" from its dashboard) runs it again, so adding the product to the config credits it.
 * Only a failed database write gives a 500 (so RevenueCat retries).
 */
@Service
class RevenueCatWebhookService(
    private val events: RevenueCatEventRepository,
    private val profiles: UserProfileRepository,
    private val ledger: LedgerService,
    private val entitlements: EntitlementService,
    private val config: AppConfigService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(body: Map<String, Any?>) {
        val e = body["event"] as? Map<*, *> ?: throw ApiException.validation("event is required")
        val id = e["id"]?.toString()?.takeIf { it.isNotBlank() } ?: throw ApiException.validation("event.id is required")
        val type = e["type"]?.toString().orEmpty()
        val from = e["transferred_from"].strList()
        val to = e["transferred_to"].strList()
        // TRANSFER events carry no app_user_id; the column is NOT NULL, so use the receiving id.
        val appUserId = e["app_user_id"]?.toString()?.ifBlank { null }
            ?: e["original_app_user_id"]?.toString()?.ifBlank { null }
            ?: to.firstOrNull() ?: from.firstOrNull() ?: ""

        val row = dbWrite {
            val existing = events.findById(id).orElse(null)
            if (existing?.processedAt != null) return@dbWrite null
            events.save(existing ?: RevenueCatEvent(
                id = id, type = type.take(48), appUserId = appUserId.take(128),
                productId = e["product_id"]?.toString(), transactionId = e["transaction_id"]?.toString(),
                environment = e["environment"]?.toString()?.take(16), payload = body,
            ))
        } ?: return

        val outcome = try {
            act(type, appUserId, e, from, to)
        } catch (ex: Exception) {
            log.warn("upstream=revenuecat-webhook event={} type={} uid={} failed: {}", id, type, uidHash(appUserId), ex.javaClass.simpleName)
            row.outcome = ERROR
            row.updatedAt = Instant.now()
            dbWrite { events.save(row) }
            // Not processed: a RevenueCat retry runs it again.
            throw ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Webhook processing failed", cause = ex)
        }
        if (outcome == UNMATCHED_PRODUCT) {
            row.outcome = outcome
            row.updatedAt = Instant.now()
            dbWrite { events.save(row) }
            log.warn("revenuecat event={} type={} uid={} product={} not in app_config ledger.packs: not credited, left unprocessed",
                id, type, uidHash(appUserId), e["product_id"]?.toString()?.take(100))
            return
        }
        row.outcome = outcome
        row.processedAt = Instant.now()
        row.updatedAt = row.processedAt!!
        dbWrite { events.save(row) }
        log.info("revenuecat event={} type={} uid={} outcome={}", id, type, uidHash(appUserId), outcome)
    }

    private fun act(type: String, uid: String, e: Map<*, *>, from: List<String>, to: List<String>): String {
        if (type == "TEST") return IGNORED
        if (type == "TRANSFER") {
            (from + to).forEach { entitlements.evict(it) }
            return EVICTED
        }
        if (uid.isBlank() || uid.startsWith("\$RCAnonymousID:") || !profiles.existsById(uid)) return IGNORED
        entitlements.evict(uid)
        val pack = e["product_id"]?.toString()?.let { config.ledger().packs[it] }
        val tx = e["transaction_id"]?.toString()?.ifBlank { null }
        return when (type) {
            "INITIAL_PURCHASE", "RENEWAL", "UNCANCELLATION", "PRODUCT_CHANGE", "SUBSCRIPTION_EXTENDED",
            "TEMPORARY_ENTITLEMENT_GRANT", "EXPIRATION", "BILLING_ISSUE", "SUBSCRIPTION_PAUSED" -> EVICTED
            "NON_RENEWING_PURCHASE" -> when {
                pack == null -> UNMATCHED_PRODUCT
                tx != null -> {
                    ledger.creditPurchased(uid, pack, Reason.PACK_PURCHASE, tx)
                    CREDITED
                }
                else -> IGNORED
            }
            "CANCELLATION" -> if (e["cancel_reason"] == "CUSTOMER_SUPPORT" && pack != null && tx != null) {
                ledger.debitPurchasedFloor(uid, pack, Reason.PACK_REFUND, "refund:$tx")
                DEBITED
            } else EVICTED
            else -> IGNORED
        }
    }

    /** A failed database write → 500, so RevenueCat retries. */
    private fun <T> dbWrite(block: () -> T): T = try {
        block()
    } catch (ex: ApiException) {
        throw ex
    } catch (ex: Exception) {
        throw ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Database write failed", cause = ex)
    }

    private fun Any?.strList(): List<String> = (this as? List<*>)?.mapNotNull { it?.toString()?.ifBlank { null } } ?: emptyList()

    companion object {
        const val CREDITED = "credited"
        const val DEBITED = "debited"
        const val EVICTED = "evicted"
        const val IGNORED = "ignored"
        /** Pack purchase for a product not in the config: stored unprocessed so a later delivery retries it. */
        const val UNMATCHED_PRODUCT = "unmatched_product"
        const val ERROR = "error"
    }
}
