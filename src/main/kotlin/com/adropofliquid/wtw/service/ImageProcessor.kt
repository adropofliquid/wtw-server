package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.config.WtwProperties
import org.springframework.stereotype.Service
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Every stored image goes through here: decode (PNG, JPEG, WebP — whatever the bytes are, not the extension),
 * shrink so the longest side ≤ IMAGE_MAX_SIDE (never upscale), encode WebP at IMAGE_WEBP_QUALITY.
 * WebP via the `com.github.usefulness:webp-imageio` ImageIO plugin (DECISIONS D-010).
 */
@Service
class ImageProcessor(private val props: WtwProperties) {

    /** Decodes [bytes] or throws 400 VALIDATION if they are not an image. */
    fun decode(bytes: ByteArray): BufferedImage =
        runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: throw ApiException.validation("Not a readable image (png, jpeg or webp)")

    fun toWebp(bytes: ByteArray, maxSide: Int = props.image.maxSide, quality: Int = props.image.webpQuality): ByteArray =
        encodeWebp(shrink(decode(bytes), maxSide), quality)

    fun toPng(bytes: ByteArray, maxSide: Int = props.image.maxSide): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(shrink(decode(bytes), maxSide), "png", out)
        return out.toByteArray()
    }

    fun shrink(src: BufferedImage, maxSide: Int): BufferedImage {
        val longest = maxOf(src.width, src.height)
        val scale = if (longest > maxSide) maxSide.toDouble() / longest else 1.0
        val w = maxOf(1, Math.round(src.width * scale).toInt())
        val h = maxOf(1, Math.round(src.height * scale).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    fun encodeWebp(img: BufferedImage, quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByMIMEType("image/webp").asSequence().firstOrNull()
            ?: error("No WebP ImageIO writer on the classpath")
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionType = compressionTypes.first { it.equals("Lossy", ignoreCase = true) }
            compressionQuality = quality.coerceIn(0, 100) / 100f
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
