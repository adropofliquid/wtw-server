package com.adropofliquid.wtw.client.rembg

import com.adropofliquid.wtw.service.ApiException

/** Background removal (brief 4.5). Returns a transparent PNG. Throws ApiException 502/504 on failure. */
fun interface BackgroundRemovalClient {
    fun remove(photo: ByteArray): ByteArray
}
