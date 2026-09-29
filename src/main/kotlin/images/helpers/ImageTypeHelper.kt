package com.mashiverse.images.helpers

import com.mashiverse.data.models.ImageType
import com.mashiverse.utils.helpers.indexOfSequence

fun getMime(imageType: ImageType): String {
    return try {
        when (imageType) {
            ImageType.SVG -> "image/svg+xml"
            ImageType.WEBP -> "image/webp"
            else -> ""
        }
    } catch (e: Exception) {
        println(e)
        "image/webp"
    }
}

fun getImageType(data: ByteArray): ImageType {
    return try {
        val size = data.size
        if (size < 4) return ImageType.UNKNOWN

        // 1. Robust SVG / XML Wrapper Checking
        // Read up to the first 1024 bytes (or full size if smaller) to check for SVG keywords
        val searchBufferSize = minOf(size, 1024)
        val headerString = String(data.sliceArray(0 until searchBufferSize), Charsets.UTF_8)

        if (headerString.contains("<svg", ignoreCase = true)) {
            return ImageType.SVG
        }

        // 2. Convert first 4 bytes to Hex string for traditional magic number matching
        val hexMarker = data.take(4).joinToString("") { "%02X".format(it) }

        when {
            hexMarker.startsWith("47494638") -> ImageType.GIF // GIF87a or GIF89a
            hexMarker == "52494646" && size >= 12 && String(data.sliceArray(8..11)) == "WEBP" -> ImageType.WEBP
            data.indexOfSequence("acTL".toByteArray()) != -1 -> ImageType.APNG
            hexMarker == "89504E47" -> ImageType.PNG
            else -> ImageType.UNKNOWN
        }
    } catch (e: Exception) {
        println(e)
        ImageType.UNKNOWN
    }
}