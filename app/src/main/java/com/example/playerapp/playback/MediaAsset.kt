package com.example.playerapp.playback

/**
 * Immutable representation of one playable asset in the kiosk loop.
 *
 * NOTE: This is intentionally decoupled from server DTOs / Repository models.
 */
data class MediaAsset(
    val id: String,
    val url: String,
    val type: AssetType,
)

enum class AssetType {
    VIDEO,
    IMAGE,
}

