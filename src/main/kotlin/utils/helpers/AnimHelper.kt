package com.mashiverse.utils.helpers

data class ImageAnimationResult(
    val isAnimated: Boolean,
    val format: String = "unknown"
)

fun isImageAnimated(bytes: ByteArray): ImageAnimationResult {
    if (bytes.size < 12) return ImageAnimationResult(isAnimated = false)

    // 1. Check GIF (GIF87a or GIF89a with > 1 frame)
    if (isAnimatedGif(bytes)) {
        return ImageAnimationResult(isAnimated = true, format = "GIF")
    }

    // 2. Check APNG
    if (isApng(bytes)) {
        return ImageAnimationResult(isAnimated = true, format = "APNG")
    }

    // 3. Check WebP
    if (isAnimatedWebP(bytes)) {
        return ImageAnimationResult(isAnimated = true, format = "WebP")
    }

    return ImageAnimationResult(isAnimated = false)
}

/**
 * Parses GIF block structure to guarantee 100% accurate frame counts
 * without misinterpreting pixel/palette bytes or stopping on size limits.
 */
private fun isAnimatedGif(bytes: ByteArray): Boolean {
    // 1. Validate Header ('GIF87a' or 'GIF89a')
    if (bytes.size < 13) return false
    val isGifHeader = bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()

    if (!isGifHeader) return false

    // 2. Read Logical Screen Descriptor
    val packedFields = bytes[10].toInt() and 0xFF
    val hasGlobalColorTable = (packedFields and 0x80) != 0
    val globalColorTableSize = 1 shl ((packedFields and 0x07) + 1)

    // 3. Skip Header (13 bytes) + Global Color Table
    var offset = 13
    if (hasGlobalColorTable) {
        offset += 3 * globalColorTableSize
    }

    var frameCount = 0

    // 4. Traverse GIF Blocks directly
    while (offset < bytes.size) {
        val blockType = bytes[offset].toInt() and 0xFF

        when (blockType) {
            0x2C -> { // Image Descriptor (Frame Header)
                frameCount++
                if (frameCount > 1) return true // Multiple frames confirmed

                // Skip Image Descriptor fixed payload (10 bytes)
                if (offset + 10 >= bytes.size) return false
                val localPacked = bytes[offset + 9].toInt() and 0xFF
                val hasLocalColorTable = (localPacked and 0x80) != 0
                val localColorTableSize = 1 shl ((localPacked and 0x07) + 1)

                offset += 10
                if (hasLocalColorTable) {
                    offset += 3 * localColorTableSize
                }

                // Skip LZW Minimum Code Size byte
                offset++

                // Skip compressed pixel data sub-blocks
                offset = skipSubBlocks(bytes, offset)
            }
            0x21 -> { // Extension Block (Graphic Control, Metadata, etc.)
                offset += 2
                offset = skipSubBlocks(bytes, offset)
            }
            0x3B -> { // GIF Trailer (End of file)
                break
            }
            else -> { // Malformed data or unknown block
                break
            }
        }
    }

    return false
}

/**
 * Safely skips linked GIF data sub-blocks.
 */
private fun skipSubBlocks(bytes: ByteArray, startOffset: Int): Int {
    var offset = startOffset
    while (offset < bytes.size) {
        val blockSize = bytes[offset].toInt() and 0xFF
        offset++
        if (blockSize == 0) break // Block terminator
        offset += blockSize
    }
    return offset
}

/**
 * Scans PNG chunks for the 'acTL' (Animation Control) chunk
 */
private fun isApng(bytes: ByteArray): Boolean {
    val pngHeader = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )
    if (bytes.size < 8 || !bytes.sliceArray(0..7).contentEquals(pngHeader)) {
        return false
    }

    val searchLimit = minOf(bytes.size - 4, 4096)
    for (i in 8 until searchLimit) {
        if (bytes[i] == 0x61.toByte() &&
            bytes[i + 1] == 0x63.toByte() &&
            bytes[i + 2] == 0x54.toByte() &&
            bytes[i + 3] == 0x4C.toByte()
        ) {
            return true
        }
    }
    return false
}

/**
 * Scans WebP RIFF container for "ANIM" chunk
 */
private fun isAnimatedWebP(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false

    val isRiff = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
    val isWebp = bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()

    if (!isRiff || !isWebp) return false

    val searchLimit = minOf(bytes.size - 4, 2048)
    for (i in 12 until searchLimit) {
        if (bytes[i] == 'A'.code.toByte() &&
            bytes[i + 1] == 'N'.code.toByte() &&
            bytes[i + 2] == 'I'.code.toByte() &&
            bytes[i + 3] == 'M'.code.toByte()
        ) {
            return true
        }
    }
    return false
}