package com.adropofliquid.wtw.client.revenuecat

/** Asks RevenueCat whether a uid has the premium entitlement now. Throws on any upstream error. */
fun interface RevenueCatClient {
    fun isPremium(uid: String): Boolean
}
