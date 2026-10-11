package com.adropofliquid.wtw.client.revenuecat

import com.adropofliquid.wtw.config.WtwProperties
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** Not prod: no network. Premium = uid listed in RC_DEV_PREMIUM_UIDS. */
@Component
@Profile("!prod")
class StubRevenueCatClient(private val props: WtwProperties) : RevenueCatClient {
    override fun isPremium(uid: String) =
        props.revenuecat.devPremiumUids.split(",").map { it.trim() }.filter { it.isNotEmpty() }.contains(uid)
}
