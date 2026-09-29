package data.db.entities

import java.time.Instant
import java.util.UUID

data class HistoryRecord(
    val id: UUID,
    val wallet: String,
    val image: ByteArray?,
    val timestamp: Instant
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HistoryRecord

        if (id != other.id) return false
        if (wallet != other.wallet) return false
        if (!image.contentEquals(other.image)) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + wallet.hashCode()
        result = 31 * result + (image?.contentHashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        return result
    }
}