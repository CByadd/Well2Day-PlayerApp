package com.example.playerapp.data

/**
 * App Configuration
 * 
 * Configure default asset URL here.
 * This will be used as a fallback if the server doesn't provide a default asset.
 * 
 * To change the default asset:
 * 1. Update DEFAULT_ASSET_URL below with your asset URL
 * 2. Rebuild the app
 */
object AppConfig {
    /**
     * Default Asset Configuration
     * 
     * This is the fallback default asset that will be used if:
     * - Server doesn't provide a default asset
     * - Network is unavailable
     * - Server returns an error
     * 
     * Change this URL to your default asset:
     * Current URL matches server .env configuration
     */
    const val DEFAULT_ASSET_URL = "https://res.cloudinary.com/dh0ehlpkp/image/upload/v1773147397/Defaut-Img_bc0xax.png"
    
    /**
     * Default Asset Name
     */
    const val DEFAULT_ASSET_NAME = "Default Asset"
    
    /**
     * Default Asset Type (image or video)
     */
    const val DEFAULT_ASSET_TYPE = "image"
    
    /**
     * Default Asset Duration (in seconds)
     */
    const val DEFAULT_ASSET_DURATION = 10
    
    /**
     * Get a DefaultAsset object with the configured values
     */
    fun getLocalDefaultAsset(localPath: String? = null): DefaultAsset {
        return DefaultAsset(
            id = -1, // Local asset has ID -1
            assetUrl = localPath ?: DEFAULT_ASSET_URL,
            assetName = DEFAULT_ASSET_NAME,
            assetType = DEFAULT_ASSET_TYPE,
            duration = DEFAULT_ASSET_DURATION,
            isActive = true
        )
    }
}

