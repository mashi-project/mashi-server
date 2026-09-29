package com.mashiverse.data.models

data class ImageDetails(
    val name: String ?= null,
    val data: ByteArray? = null,
    val imageType: ImageType,
    val mimeType: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImageDetails

        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        return data.contentHashCode()
    }
}
