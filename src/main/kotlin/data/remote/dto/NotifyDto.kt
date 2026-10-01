package com.mashiverse.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NotifyDto(
    val title: String,
    val artistName: String,
    val artistWallet: String,
    val docId: String,
    val approvalUrl: String,
    val tokenURI: String,
    val assets: AssetsDto,
    val listing: ListingDto? = null
) {
    @Serializable
    data class AssetsDto(
        val composite: String,
        val eyes: String,
        val head: String,
        val upper: String,
        val bottom: String,
        val cape: String? = null,
        @SerialName("hair_back") val hairBack: String? = null,
        @SerialName("hair_front") val hairFront: String? = null,
        val hat: String? = null,
        @SerialName("left_accessory") val leftAccessory: String? = null,
        @SerialName("right_accessory") val rightAccessory: String? = null,
        val background: String
    )

    @Serializable
    data class ListingDto(
        val listingId: String = "",
        val marketplace: String = "",
        val priceMatic: Double = 0.0,
        val maxSupply: Int = -1,
        val maxPerWallet: Int = -1
    )

}

