package com.adropofliquid.wtw.client.rembg

import com.adropofliquid.wtw.service.ImageProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** REMBG_MODE=stub (dev default): no network. The photo back as a PNG, longest side 600 (like rembg with MAX_SIDE=600). */
@Component
@ConditionalOnProperty(name = ["wtw.rembg.mode"], havingValue = "stub")
class StubBackgroundRemovalClient(private val images: ImageProcessor) : BackgroundRemovalClient {
    override fun remove(photo: ByteArray): ByteArray = images.toPng(photo, 600)
}
