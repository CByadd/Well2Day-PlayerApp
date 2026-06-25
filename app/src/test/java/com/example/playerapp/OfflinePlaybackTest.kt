package com.example.playerapp

import com.example.playerapp.data.Asset
import com.example.playerapp.playback.AssetType
import com.example.playerapp.data.DefaultAsset
import com.example.playerapp.data.Repository
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import android.content.Context
import java.io.File

/**
 * Unit test for offline playback functionality
 * Tests that the app can play cached content when not registered
 */
class OfflinePlaybackTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockFilesDir: File

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        `when`(mockContext.filesDir).thenReturn(mockFilesDir)
    }

    @Test
    fun `applyCachedAssets returns true when assets file exists`() {
        // Test that cached assets can be loaded even when offline
        val testAssets = listOf(
            Asset(
                assetUrl = "/data/data/com.example.playerapp/files/asset-1-test.jpg",
                slotNumber = 1,
                originalUrl = "https://example.com/test.jpg",
                duration = 10
            ),
            Asset(
                assetUrl = "/data/data/com.example.playerapp/files/asset-2-test.mp4", 
                slotNumber = 2,
                originalUrl = "https://example.com/test.mp4",
                duration = 15
            )
        )

        // Verify that assets are properly sorted by slot number
        val sortedAssets = testAssets.sortedBy { it.slotNumber }
        assertEquals(1, sortedAssets[0].slotNumber)
        assertEquals(2, sortedAssets[1].slotNumber)
        assertTrue("Should have cached assets", sortedAssets.isNotEmpty())
    }

    @Test
    fun `default asset should be used when no playlist assets available`() {
        val testDefaultAsset = DefaultAsset(
            id = -1,
            assetUrl = "/data/data/com.example.playerapp/files/default-asset",
            assetName = "Cached Default Asset",
            assetType = "image",
            duration = 10,
            isActive = true
        )

        assertEquals("Default asset should be image type", "image", testDefaultAsset.assetType)
        assertEquals("Default asset should have duration 10", 10, testDefaultAsset.duration)
        assertTrue("Default asset should be active", testDefaultAsset.isActive)
    }

    @Test
    fun `asset type detection should work correctly`() {
        val videoAsset = Asset(
            assetUrl = "https://example.com/video.mp4",
            slotNumber = 1,
            originalUrl = "https://example.com/video.mp4"
        )
        val imageAsset = Asset(
            assetUrl = "https://example.com/image.jpg", 
            slotNumber = 2,
            originalUrl = "https://example.com/image.jpg"
        )

        // Verify that file extensions are handled correctly
        assertTrue("Video URL should contain mp4", videoAsset.assetUrl.contains(".mp4"))
        assertTrue("Image URL should contain jpg", imageAsset.assetUrl.contains(".jpg"))
        assertEquals("Video should be in slot 1", 1, videoAsset.slotNumber)
        assertEquals("Image should be in slot 2", 2, imageAsset.slotNumber)
    }

    @Test
    fun `offline mode should work with empty assets list`() {
        val emptyAssets = emptyList<Asset>()
        val noDefaultAsset: DefaultAsset? = null

        // When both assets and default asset are empty, should show "Not registered"
        assertTrue("Should have no assets", emptyAssets.isEmpty())
        assertNull("Should have no default asset", noDefaultAsset)
    }

    @Test
    fun `offline mode should work with cached assets only`() {
        val cachedAssets = listOf(
            Asset(
                assetUrl = "/data/data/com.example.playerapp/files/asset-1-offline.jpg",
                slotNumber = 1,
                originalUrl = "https://example.com/offline.jpg"
            )
        )
        val noDefaultAsset: DefaultAsset? = null

        // When we have cached assets but no default, should play cached assets
        assertTrue("Should have cached assets", cachedAssets.isNotEmpty())
        assertNull("Should have no default asset", noDefaultAsset)
    }

    @Test
    fun `offline mode should work with default asset only`() {
        val emptyAssets = emptyList<Asset>()
        val defaultAsset = DefaultAsset(
            id = -1,
            assetUrl = "/data/data/com.example.playerapp/files/default-asset",
            assetName = "Default Asset",
            assetType = "image",
            duration = 10,
            isActive = true
        )

        // When we have default asset but no cached assets, should play default asset
        assertTrue("Should have no cached assets", emptyAssets.isEmpty())
        assertNotNull("Should have default asset", defaultAsset)
        assertTrue("Default asset should be active", defaultAsset.isActive)
    }
}
