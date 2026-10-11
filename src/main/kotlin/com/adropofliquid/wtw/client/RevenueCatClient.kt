package com.adropofliquid.wtw.client

import com.adropofliquid.wtw.config.WtwProperties
import org.springframework.context.annotation.Profile
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.net.http.HttpClient
import java.time.Duration

/** Asks RevenueCat whether a uid has the premium entitlement now. Throws on any upstream error. */
fun interface RevenueCatClient {
    fun isPremium(uid: String): Boolean
}

/**
 * prod: REST v2 `GET /v2/projects/{RC_PROJECT_ID}/customers/{uid}` → premium if
 * `active_entitlements.items[].entitlement_id == RC_ENTITLEMENT_ID` (same check as the old RevenueCatUtils).
 */
@Component
@Profile("prod")
class RestRevenueCatClient(private val props: WtwProperties) : RevenueCatClient {
    private val http = RestClient.builder()
        .baseUrl("https://api.revenuecat.com")
        .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
            .apply { setReadTimeout(Duration.ofSeconds(10)) })
        .build()

    override fun isPremium(uid: String): Boolean {
        val rc = props.revenuecat
        val body = http.get().uri("/v2/projects/{p}/customers/{u}", rc.projectId, uid)
            .header("Authorization", "Bearer ${rc.apiKey}")
            .retrieve().body<Map<String, Any?>>() ?: return false
        val items = ((body["active_entitlements"] as? Map<*, *>)?.get("items") as? List<*>) ?: return false
        return items.any { (it as? Map<*, *>)?.get("entitlement_id") == rc.entitlementId }
    }
}

/** Not prod: no network. Premium = uid listed in RC_DEV_PREMIUM_UIDS. */
@Component
@Profile("!prod")
class StubRevenueCatClient(private val props: WtwProperties) : RevenueCatClient {
    override fun isPremium(uid: String) =
        props.revenuecat.devPremiumUids.split(",").map { it.trim() }.filter { it.isNotEmpty() }.contains(uid)
}
