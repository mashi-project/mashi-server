package com.mashiverse.data.db.entities

import com.mashiverse.data.models.ImageType

data class Image(
    val url: String,
    val data: ByteArray? = null,
    val type: ImageType
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Image

        if (url != other.url) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + (data?.contentHashCode() ?: 0)
        return result
    }
}
