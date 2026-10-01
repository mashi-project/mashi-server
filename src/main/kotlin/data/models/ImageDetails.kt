package com.mashiverse.data.models

data class ImageDetails(
    val name: String? = null,
    val data: ByteArray? = null,
    val imageType: ImageType,
    val mimeType: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImageDetails

        if (name != other.name) return false
        if (imageType != other.imageType) return false
        if (mimeType != other.mimeType) return false

        // Safe content comparison for nullable ByteArrays
        if (data === other.data) return true
        if (data == null || other.data == null) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (data?.contentHashCode() ?: 0)
        result = 31 * result + imageType.hashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}