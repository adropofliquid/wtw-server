package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.client.storage.Bucket
import com.adropofliquid.wtw.client.storage.Keys
import com.adropofliquid.wtw.client.storage.ObjectStorage
import com.adropofliquid.wtw.config.RequestLoggingFilter.Companion.uidHash
import com.adropofliquid.wtw.repository.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Route 28, DELETE /account (brief 4.9). Idempotent. Steps, in order:
 * 1. delete every object under `users/{uid}/` in the user bucket;
 * 2. in one transaction, delete every row with this user_id (no FKs cascade, ruling 9.1), then the profile row;
 * 3. delete revenuecat_event rows for this app_user_id;
 * 4. evict the uid from the caches and log one line.
 * The Firebase user and the RevenueCat customer are not touched. The auth filter makes a fresh stub
 * profile if the same token is used again; that is expected.
 */
@Service
class AccountService(
    private val storage: ObjectStorage,
    private val items: MirrorItemRepository,
    private val outfits: MirrorOutfitRepository,
    private val pieces: MirrorOutfitPieceRepository,
    private val planned: MirrorPlannedRepository,
    private val wear: MirrorWearRepository,
    private val lists: MirrorPackingListRepository,
    private val packItems: MirrorPackingItemRepository,
    private val sessions: AiSessionRepository,
    private val suggestions: AiSuggestionRepository,
    private val ledger: LedgerEntryRepository,
    private val tokens: PushTokenRepository,
    private val profiles: UserProfileRepository,
    private val rcEvents: RevenueCatEventRepository,
    private val profileService: UserProfileService,
    private val entitlements: EntitlementService,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)

    fun delete(uid: String) {
        val objects = storage.deletePrefix(Bucket.USER, Keys.userPrefix(uid))
        val counts = tx.execute {
            val c = linkedMapOf(
                "items" to items.deleteByUserId(uid),
                "outfits" to outfits.deleteByUserId(uid),
                "pieces" to pieces.deleteByUserId(uid),
                "planned" to planned.deleteByUserId(uid),
                "wear" to wear.deleteByUserId(uid),
                "packingLists" to lists.deleteByUserId(uid),
                "packingItems" to packItems.deleteByUserId(uid),
                "sessions" to sessions.deleteByUserId(uid),
                "suggestions" to suggestions.deleteByUserId(uid),
                "ledger" to ledger.deleteByUserId(uid),
                "pushTokens" to tokens.deleteByUserId(uid),
            )
            c["profile"] = if (profiles.existsById(uid)) { profiles.deleteById(uid); 1L } else 0L
            c
        }!!
        val rc = tx.execute { rcEvents.deleteByAppUserId(uid) }!!
        profileService.forget(uid)
        entitlements.evict(uid)
        log.info("account deleted uid={} objects={} rows={} rcEvents={}", uidHash(uid), objects, counts, rc)
    }
}
