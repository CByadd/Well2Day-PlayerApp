package com.example.playerapp.data

data class Asset(
    val assetUrl: String, 
    val slotNumber: Int,
    val originalUrl: String? = null, // Store original Cloudinary URL for live preview
    val duration: Int? = null // Duration in seconds (for images, null for videos means use video's actual duration)
)
