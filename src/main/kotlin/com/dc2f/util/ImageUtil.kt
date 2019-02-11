package com.dc2f.util

import mu.KotlinLogging
import java.awt.Color
import java.awt.image.BufferedImage
import java.net.URL
import java.nio.file.*
import javax.imageio.ImageIO

private val logger = KotlinLogging.logger {}

data class ImageInfo(
    val width: Int,
    val height: Int,
    val mimeType: String,
    val mimeTypes: List<String>,
    val fileName: String,
    val brightnessRatio: Double?
)

object ImageUtil {
    fun readImageData(path: Path): ImageInfo? {
        ImageIO.createImageInputStream(path.toFile().inputStream()).use { imageInput ->
            val reader = ImageIO.getImageReaders(imageInput).takeIf { it.hasNext() }?.next()

            if (reader == null) {
                logger.error { "Unable to read image. $path" }
                return null
            }

            reader.setInput(imageInput, false, false)

            val format = reader.formatName
//        reader?.
            val mimeTypes = reader.originatingProvider.mimeTypes
            logger.debug { "MimeTypes for ${format}: ${mimeTypes?.contentToString()}" }
            if (mimeTypes.size != 1) {
                logger.warn { "No unique mime type for ${format} $path: ${mimeTypes?.contentToString()}" }
            }
            val mimeType = mimeTypes.first()

            val numImages = reader.getNumImages(true)
            val largestImage = (0 until numImages).mapNotNull { i ->
                try {
                    i to Pair(reader.getWidth(i), reader.getHeight(i))
                } catch (e: Throwable) {
                    logger.error(e) { "Unable to parse $path (${i})" }
                    null
                }
            }
                .sortedBy { it.second.first }
                .also { logger.debug { "Image Sizes: ${it}" } }
                .firstOrNull()

            if (largestImage == null) {
                logger.debug("No valid image size found for $path")
                return null
            }

            val brightnessRatio = try {
                analyseImageBrightnessRatio(reader.read(largestImage.first))
            } catch (e: Throwable) {
                logger.error(e) { "Unable to read image from $path" }
                null
            }

            return ImageInfo(
                largestImage.second.first,
                largestImage.second.second,
                mimeType,
                mimeTypes.toList(),
                path.fileName.toString(),
                brightnessRatio
            )
        }
    }

    /**
     * returns the ratio of dark pixel to bright pixels.
     * https://stackoverflow.com/a/35914745/109219
     */
    private fun analyseImageBrightnessRatio(image: BufferedImage): Double {
        val histogram = Array<Int>(256) { 0 }

        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                val rgb = Color(image.getRGB(x, y))
                val brightness = (0.2126 * rgb.red + 0.7152 * rgb.green + 0.0722 * rgb.blue).toInt()
                histogram[brightness]++
            }
        }

        // Count pixels with brightness less then 10
        val darkPixelCount = histogram.take(10).sum()

        return darkPixelCount.toDouble() / (image.width * image.height)
    }
}