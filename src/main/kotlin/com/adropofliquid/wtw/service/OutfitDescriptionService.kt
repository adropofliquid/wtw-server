package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.client.gemini.ChatMessage
import com.adropofliquid.wtw.client.gemini.GeminiCallLog
import com.adropofliquid.wtw.client.gemini.GeminiClient
import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.dto.DescribeRequest
import com.adropofliquid.wtw.dto.DescribeResponse
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

/**
 * Route 16 (brief 4.4): a stylist review of an outfit the user made, `{review, occasion}`.
 * Port of the old outfit review prompt (fixed: the old one described the fields wrongly). Costs
 * cost_outfit_description: charged first, Gemini called outside any transaction, refunded on any failure.
 */
@Service
class OutfitDescriptionService(
    private val gemini: GeminiClient,
    private val prompts: PromptBuilder,
    private val ledger: LedgerService,
    private val config: AppConfigService,
    private val props: WtwProperties,
    private val json: JsonMapper,
) {
    fun describe(uid: String, req: DescribeRequest): Metered<DescribeResponse> {
        val items = req.items.orEmpty()
        if (items.isEmpty()) throw ApiException.validation("items is required")
        if (items.size > MAX_ITEMS) throw ApiException.validation("items has more than $MAX_ITEMS entries")
        items.forEach { e ->
            if (e.category?.lowercase() !in CATEGORIES) throw ApiException.validation("items[].category must be one of ${CATEGORIES.joinToString()}")
        }
        val clean = items.map { it.copy(category = it.category!!.lowercase()) }
        val occasion = req.occasion?.trim()?.ifBlank { null }?.take(200)
        val message = prompts.describeUserMessage(clean, occasion)

        val cost = config.ledger().costOutfitDescription
        val charge = ledger.spend(uid, Reason.OUTFIT_DESCRIPTION, cost)
        try {
            val r = gemini.chat(props.ai.modelSuggest, prompts.describeOutfit, listOf(ChatMessage("user", message)))
            GeminiCallLog.log("ai/describe-outfit", r.usage, cost)
            val map = parseJson(r.text) ?: throw ApiException.upstreamFailed("Gemini (review reply is not JSON)")
            val review = (map["review"] as? String)?.trim()?.ifBlank { null } ?: throw ApiException.upstreamFailed("Gemini (no review)")
            val occ = (map["occasion"] as? String)?.trim()?.ifBlank { null } ?: occasion ?: ""
            return Metered(charge.state.balance, DescribeResponse(review, occ))
        } catch (e: Exception) {
            ledger.refund(uid, charge)
            throw e as? ApiException ?: ApiException.upstreamFailed("Gemini", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(text: String): Map<String, Any?>? {
        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { json.readValue(clean, Map::class.java) as Map<String, Any?> }.getOrNull()
    }

    private companion object {
        const val MAX_ITEMS = 20
        val CATEGORIES = listOf("tops", "bottoms", "outerwear", "shoes", "accessories")
    }
}
