package com.example.playerapp.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import com.example.playerapp.utils.getIsoTimestamp
import com.example.playerapp.data.DefaultAsset
import com.example.playerapp.data.AppConfig
import com.example.playerapp.utils.InfoStorage
import com.example.playerapp.data.local.AppDatabase
import com.example.playerapp.data.local.BmiEntity
import kotlinx.coroutines.*
import androidx.work.*
import com.example.playerapp.data.BmiSyncWorker
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.*

//const val BASE_URL = "http://localhost:4000/api"
// Updated to point to Server instead of Adscape-Server
// TODO: Update this URL to your server deployment URL
//const val BASE_URL = "https://bmi-server-eight.vercel.app/api" // Change to your server URL
//const val BASE_URL = "https://wan-changeable-efferently.ngrok-free.dev/api"
const val BASE_URL = "https://api.well2day.in/api"
//const val BASE_URL = " https://relieved-sparrow-fairly.ngrok-free.app/api" // Removed leading space
object Repository {
    private const val ASSET_DOWNLOAD_CONCURRENCY = 4
    private val SERVER_ROOT_URL = BASE_URL.removeSuffix("/api").trimEnd('/')

    // General-purpose API client — short timeouts to fail fast on flaky networks
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    // Long-timeout client used exclusively for large asset downloads
    private val assetDownloadClient = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // Callback to track API call states
    var onApiCallStateChanged: ((Boolean) -> Unit)? = null
    
    private fun notifyApiCallStarted() {
        Handler(Looper.getMainLooper()).post {
            onApiCallStateChanged?.invoke(true)
        }
    }
    
    private fun notifyApiCallFinished() {
        Handler(Looper.getMainLooper()).post {
            onApiCallStateChanged?.invoke(false)
        }
    }

    fun registerScreen(
        machineId: String,
        screenId: String,
        resolution: String,
        os: String,
        appVersion: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        notifyApiCallStarted()
        val mediaType = "application/json".toMediaType()

        val body = """{
        "machineId":"$machineId",
        "screenId":"$screenId",
        "resolution":"$resolution",
        "os":"$os",
        "appVersion":"$appVersion"
    }""".trimIndent().toRequestBody(mediaType)

        val request = Request.Builder()
            .url("$BASE_URL/players/register")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                Handler(Looper.getMainLooper()).post {
                    onResult(false, "Failed: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                response.use {
                    if (!response.isSuccessful) {
                        Handler(Looper.getMainLooper()).post {
                            onResult(false, "Server error: ${response.code}")
                        }
                        return
                    }
                    Handler(Looper.getMainLooper()).post {
                        onResult(true, null)
                    }
                }
            }
        })
    }

    fun fetchAssets(context: Context, screenId: String, onResult: (List<Asset>?, String?) -> Unit) {
        fetchAssetsWithRetry(context, screenId, null, 0, 3, onResult)
    }
    
    fun fetchAssetsForDate(context: Context, screenId: String, date: String, onResult: (List<Asset>?, String?) -> Unit) {
        fetchAssetsWithRetry(context, screenId, date, 0, 3, onResult)
    }
    
    fun fetchTomorrowAssets(context: Context, screenId: String, onResult: (List<Asset>?, String?) -> Unit) {
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val tomorrowDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(calendar.time)
        println("DEBUG: Repository.fetchTomorrowAssets - Fetching assets for date: $tomorrowDate")
        fetchAssetsForDate(context, screenId, tomorrowDate, onResult)
    }
    
    fun deleteYesterdayAssets(context: Context) {
        try {
            val calendar = java.util.Calendar.getInstance()
            calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
            val yesterdayDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(calendar.time)
            println("DEBUG: Repository.deleteYesterdayAssets - Deleting assets for date: $yesterdayDate")
            
            // Delete asset files for yesterday
            val filesDir = context.filesDir
            val assetFiles = filesDir.listFiles { _, name -> name.startsWith("asset-") }
            
            if (assetFiles != null) {
                // Load current assets to check dates
                val currentAssets = loadAssetsFromFile(context)
                val assetsToKeep = mutableListOf<Asset>()
                
                for (asset in currentAssets) {
                    // Check if asset file exists and is recent (not from yesterday)
                    val slotNum = asset.slotNumber
                    val assetFile = File(filesDir, "asset-$slotNum")
                    if (assetFile.exists()) {
                        val fileDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                            .format(java.util.Date(assetFile.lastModified()))
                        if (fileDate != yesterdayDate) {
                            assetsToKeep.add(asset)
                        } else {
                            // Delete yesterday's asset file
                            assetFile.delete()
                            println("DEBUG: Repository.deleteYesterdayAssets - Deleted asset file: ${assetFile.name}")
                        }
                    } else {
                        assetsToKeep.add(asset)
                    }
                }
                
                // Save updated assets list
                saveAssetsToFile(context, assetsToKeep)
                println("DEBUG: Repository.deleteYesterdayAssets - Kept ${assetsToKeep.size} assets, deleted yesterday's assets")
            }
        } catch (e: Exception) {
            println("DEBUG: Repository.deleteYesterdayAssets - Error: ${e.message}")
            e.printStackTrace()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun fetchAssetsWithRetry(
        context: Context, 
        screenId: String,
        date: String?,
        attempt: Int, 
        maxRetries: Int, 
        onResult: (List<Asset>?, String?) -> Unit
    ) {
        notifyApiCallStarted()
        val url = if (date != null) {
            "$BASE_URL/assets/$screenId?date=$date"
        } else {
            "$BASE_URL/assets/$screenId"
        }
        println("DEBUG: Repository.fetchAssets - URL: $url (attempt ${attempt + 1}/$maxRetries)")
        
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                println("DEBUG: Repository.fetchAssets - Network failure: ${e.message}")
                
                // Try cached assets first
                val cached = loadAssetsFromFile(context)
                if (cached.isNotEmpty()) {
                    println("DEBUG: Repository.fetchAssets - Using cached assets: ${cached.size}")
                    Handler(Looper.getMainLooper()).post {
                        onResult(cached, null)
                    }
                    return
                }
                
                // If no cached assets and we have retries left, try again
                if (attempt < maxRetries - 1) {
                    println("DEBUG: Repository.fetchAssets - Retrying in ${(attempt + 1) * 2000}ms...")
                    Handler(Looper.getMainLooper()).postDelayed({
                        fetchAssetsWithRetry(context, screenId, date, attempt + 1, maxRetries, onResult)
                    }, (attempt + 1) * 2000L)
                } else {
                    println("DEBUG: Repository.fetchAssets - All attempts failed, no cached assets")
                    Handler(Looper.getMainLooper()).post {
                        onResult(null, "Failed to connect after $maxRetries attempts")
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                println("DEBUG: Repository.fetchAssets - Response code: ${response.code}")
                response.use {
                    if (!response.isSuccessful) {
                        println("DEBUG: Repository.fetchAssets - Server error: ${response.code}")
                        
                        // Try cached assets on server error
                        val cached = loadAssetsFromFile(context)
                        if (cached.isNotEmpty()) {
                            println("DEBUG: Repository.fetchAssets - Using cached assets due to server error")
                            Handler(Looper.getMainLooper()).post {
                                onResult(cached, null)
                            }
                            return
                        }
                        
                        // If no cached assets and we have retries left, try again
                        if (attempt < maxRetries - 1) {
                            println("DEBUG: Repository.fetchAssets - Retrying due to server error...")
                            Handler(Looper.getMainLooper()).postDelayed({
                                fetchAssetsWithRetry(context, screenId, date, attempt + 1, maxRetries, onResult)
                            }, (attempt + 1) * 2000L)
                        } else {
                            Handler(Looper.getMainLooper()).post {
                                onResult(null, "Server error: ${response.code}")
                            }
                        }
                        return
                    }

                    val body = response.body?.string()
                    println("DEBUG: Repository.fetchAssets - Response body length: ${body?.length}")
                    if (body.isNullOrBlank()) {
                        println("DEBUG: Repository.fetchAssets - Empty response body")
                        
                        // Try cached assets on empty response
                        val cached = loadAssetsFromFile(context)
                        if (cached.isNotEmpty()) {
                            println("DEBUG: Repository.fetchAssets - Using cached assets due to empty response")
                            Handler(Looper.getMainLooper()).post {
                                onResult(cached, null)
                            }
                            return
                        }
                        
                        Handler(Looper.getMainLooper()).post {
                            onResult(null, "Empty response")
                        }
                        return
                    }

                    try {
                        println("DEBUG: Repository.fetchAssets - Parsing JSON response")
                        val arr = JSONArray(body)
                        println("DEBUG: Repository.fetchAssets - JSON array length: ${arr.length()}")
                        val remoteAssets = mutableListOf<Asset>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val url = obj.optString("asset_url", "")
                            val slotNum = if (obj.has("slot_number")) obj.optInt("slot_number", 0) else obj.optInt("slotNumber", 0)
                            val duration = if (obj.has("duration")) obj.optInt("duration", 10) else 10
                            if (url.isBlank()) continue
                            remoteAssets.add(Asset(url, slotNum, originalUrl = url, duration = duration))
                        }

                        val previousAssets = loadAssetsFromFile(context)
                        val downloadDispatcher = Dispatchers.IO.limitedParallelism(ASSET_DOWNLOAD_CONCURRENCY)
                        val list = runBlocking {
                            remoteAssets.map { remoteAsset ->
                                async(downloadDispatcher) {
                                    resolveAssetForPlayback(context, remoteAsset, date)
                                }
                            }.awaitAll()
                        }.sortedBy { it.slotNumber }

                        saveAssetsToFile(context, list)
                        cleanupReplacedPlaylistFiles(previousAssets, list)
                        Handler(Looper.getMainLooper()).post {
                            if (list.isNotEmpty()) {
                                println("DEBUG: Repository.fetchAssets - Successfully parsed ${list.size} assets")
                                onResult(list, null)
                            } else {
                                println("DEBUG: Repository.fetchAssets - No valid assets found, trying cached assets")
                                // Fallback to cached assets if no assets could be downloaded
                                val cached = loadAssetsFromFile(context)
                                if (cached.isNotEmpty()) {
                                    println("DEBUG: Repository.fetchAssets - Using cached assets as fallback: ${cached.size}")
                                    onResult(cached, null)
                                } else {
                                    println("DEBUG: Repository.fetchAssets - No valid assets and no cached assets")
                                    onResult(null, "No valid assets")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("DEBUG: Repository.fetchAssets - Parse error: ${e.message}")
                        
                        // Try cached assets on parse error
                        val cached = loadAssetsFromFile(context)
                        if (cached.isNotEmpty()) {
                            println("DEBUG: Repository.fetchAssets - Using cached assets due to parse error")
                            Handler(Looper.getMainLooper()).post {
                                onResult(cached, null)
                            }
                        } else {
                            Handler(Looper.getMainLooper()).post {
                                onResult(null, "Parse error: ${e.message}")
                            }
                        }
                    }
                }
            }
        })
    }

    private fun downloadAssetWithRetry(url: String, localFile: File, maxRetries: Int) {
        var attempt = 0
        var lastException: Exception? = null
        
        // Always delete existing file to ensure fresh download
        if (localFile.exists()) {
            val deleted = localFile.delete()
            println("DEBUG: Repository.downloadAsset - Deleted existing file for fresh download: ${localFile.absolutePath}, deleted: $deleted")
        }
        
        while (attempt < maxRetries) {
            try {
                attempt++
                println("DEBUG: Repository.downloadAsset - Fresh download attempt $attempt/$maxRetries for $url")
                var downloadSucceeded = false
                
                // Ensure parent directory exists
                localFile.parentFile?.mkdirs()
                
                val req = Request.Builder().url(url).build()
                assetDownloadClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code}: ${resp.message}")
                    }
                    val body = resp.body ?: throw IOException("Empty response body")
                    
                    // Get expected content length for verification
                    val expectedLength = body.contentLength()
                    println("DEBUG: Repository.downloadAsset - Expected file size: $expectedLength bytes")
                    
                    if (expectedLength <= 0) {
                        println("DEBUG: Repository.downloadAsset - Warning: Server did not provide content length")
                    }
                    
                    // Download with integrity verification
                    var downloadedBytes = 0L
                    var lastProgressLog = 0L
                    val tempFile = File(localFile.absolutePath + ".tmp")
                    
                    // Delete temp file if exists
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                    
                    body.byteStream().use { input ->
                        tempFile.outputStream().use { sink ->
                            val buffer = ByteArray(16384) // 16KB buffer for better performance
                            var bytesRead: Int
                            
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (bytesRead > 0) {
                                    sink.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead
                                    
                                    // Log progress every 10% or every 5MB
                                    val progressPercent = if (expectedLength > 0) {
                                        (downloadedBytes * 100 / expectedLength).toInt()
                                    } else 0
                                    
                                    val shouldLog = (expectedLength > 0 && progressPercent > lastProgressLog + 10) ||
                                                   (downloadedBytes - lastProgressLog * 1024 * 1024 > 5)
                                    
                                    if (shouldLog) {
                                        println("DEBUG: Repository.downloadAsset - Progress: $progressPercent% ($downloadedBytes/${expectedLength}L bytes)")
                                        lastProgressLog = if (expectedLength > 0) progressPercent.toLong() else (downloadedBytes / (1024 * 1024))
                                    }
                                }
                            }
                            sink.flush()
                        }
                    }
                    
                    // Verify download integrity
                    if (tempFile.length() == 0L) {
                        throw IOException("Downloaded file is empty")
                    }
                    
                    // If we have expected length, verify it matches exactly
                    if (expectedLength > 0 && tempFile.length() != expectedLength) {
                        val actualLength = tempFile.length()
                        throw IOException("Download incomplete: expected $expectedLength bytes, got $actualLength bytes")
                    }
                    
                    // Verify file can be read (basic corruption check)
                    try {
                        tempFile.inputStream().use { input ->
                            // Try to read first few bytes to check file integrity
                            val testBuffer = ByteArray(1024)
                            val bytesRead = input.read(testBuffer)
                            if (bytesRead == -1) {
                                throw IOException("File appears corrupted - cannot read any data")
                            }
                        }
                    } catch (e: Exception) {
                        throw IOException("File corruption detected: ${e.message}")
                    }
                    
                    // Atomic move from temp to final location
                    if (tempFile.renameTo(localFile)) {
                        val finalLength = localFile.length()
                        println("DEBUG: Repository.downloadAsset - Successfully downloaded and verified: ${localFile.absolutePath} ($finalLength bytes)")
                        downloadSucceeded = finalLength > 0
                    } else {
                        throw IOException("Failed to move temp file to final location")
                    }
                }

                if (downloadSucceeded && localFile.exists() && localFile.length() > 0) {
                    return
                } else if (!downloadSucceeded) {
                    throw IOException("Download finished without producing a valid local file")
                }
            } catch (ex: Exception) {
                lastException = ex
                println("DEBUG: Repository.downloadAsset - Download attempt $attempt failed: ${ex.message}")
                
                // Clean up partial files
                val tempFile = File(localFile.absolutePath + ".tmp")
                if (tempFile.exists()) {
                    tempFile.delete()
                    println("DEBUG: Repository.downloadAsset - Deleted temp file: ${tempFile.absolutePath}")
                }
                
                if (localFile.exists()) {
                    localFile.delete()
                    println("DEBUG: Repository.downloadAsset - Deleted partial file: ${localFile.absolutePath}")
                }
                
                if (attempt < maxRetries) {
                    val retryDelay = minOf(attempt * 2000L, 10000L) // Cap retry delay at 10 seconds
                    println("DEBUG: Repository.downloadAsset - Retrying in ${retryDelay}ms...")
                    Thread.sleep(retryDelay)
                }
            }
        }
        
        println("DEBUG: Repository.downloadAsset - All download attempts failed for $url")
        println("DEBUG: Repository.downloadAsset - Last exception: ${lastException?.javaClass?.simpleName}")
        println("DEBUG: Repository.downloadAsset - Last exception message: ${lastException?.message}")
        println("DEBUG: Repository.downloadAsset - Last exception stack trace: ${lastException?.stackTraceToString()}")
        throw IOException("Failed to download after $maxRetries attempts: ${lastException?.message ?: "Unknown error"}", lastException)
    }

    private fun resolveAssetForPlayback(context: Context, remoteAsset: Asset, date: String?): Asset {
        val localFile = buildAssetFile(context, remoteAsset.slotNumber, remoteAsset.originalUrl ?: remoteAsset.assetUrl, date)

        if (!localFile.exists()) {
            try {
                downloadAssetWithRetry(remoteAsset.originalUrl ?: remoteAsset.assetUrl, localFile, 3)
                println("DEBUG: Repository.fetchAssets - Downloaded asset for slot ${remoteAsset.slotNumber} to ${localFile.name}")
            } catch (e: Exception) {
                println("DEBUG: Repository.fetchAssets - Failed to download asset for slot ${remoteAsset.slotNumber}: ${e.message}")
            }
        } else {
            println("DEBUG: Repository.fetchAssets - Reusing staged cached file for slot ${remoteAsset.slotNumber}: ${localFile.name}")
        }

        val finalUrl = if (localFile.exists()) localFile.absolutePath else (remoteAsset.originalUrl ?: remoteAsset.assetUrl)
        return remoteAsset.copy(assetUrl = finalUrl)
    }

    private fun buildAssetFile(context: Context, slotNumber: Int, remoteUrl: String, date: String?): File {
        val datePart = date?.replace("-", "")?.let { "-$it" } ?: ""
        val hash = shortHash(remoteUrl)
        val extension = extractExtension(remoteUrl)
        return File(context.filesDir, "asset-$slotNumber$datePart-$hash$extension")
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun extractExtension(url: String): String {
        val cleanUrl = url.substringBefore('?').substringBefore('#')
        val candidate = cleanUrl.substringAfterLast('/', "").substringAfterLast('.', "")
        if (candidate.isBlank()) return ""
        val normalized = candidate.lowercase()
        return if (normalized.length in 2..5 && normalized.all { it.isLetterOrDigit() }) {
            ".$normalized"
        } else {
            ""
        }
    }

    private fun normalizeServerAssetUrl(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) } ?: return null
        return when {
            raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$SERVER_ROOT_URL$raw"
            else -> "$SERVER_ROOT_URL/$raw"
        }
    }

    private fun buildAssetDownloadCandidates(url: String?): List<String> {
        val normalized = normalizeServerAssetUrl(url) ?: return emptyList()
        val candidates = linkedSetOf<String>()
        candidates.add(normalized)

        val assetPathIndex = normalized.indexOf("/assets/", ignoreCase = true)
        if (assetPathIndex >= 0) {
            val rewritten = "$SERVER_ROOT_URL${normalized.substring(assetPathIndex)}"
            if (rewritten.isNotBlank()) {
                println("DEBUG: Repository.getFlowDrawerImages - Rewriting asset URL to active server host: $normalized -> $rewritten")
                candidates.add(rewritten)
            }
        }
        return candidates.toList()
    }

    private fun persistFlowDrawerSnapshot(
        context: Context,
        screenId: String,
        slotCount: Int,
        slotUrls: List<String?>,
        localPathsBySlot: Map<Int, String>
    ) {
        runCatching {
            val snapshotFile = File(context.filesDir, "flow_drawer_snapshot_$screenId.json")
            val slotsJson = JSONArray()
            for (slot in 1..slotCount) {
                val localPath = localPathsBySlot[slot]
                val localFile = localPath?.let { File(it) }
                slotsJson.put(
                    JSONObject().apply {
                        put("slot", slot)
                        put("remoteUrl", slotUrls.getOrNull(slot - 1))
                        put("localPath", localPath)
                        put("exists", localFile?.exists() == true)
                        put("size", localFile?.takeIf { it.exists() }?.length() ?: 0L)
                    }
                )
            }

            snapshotFile.writeText(
                JSONObject().apply {
                    put("screenId", screenId)
                    put("slotCount", slotCount)
                    put("updatedAt", System.currentTimeMillis())
                    put("slots", slotsJson)
                }.toString(2)
            )
            println("DEBUG: Repository.getFlowDrawerImages - Persisted snapshot: ${snapshotFile.absolutePath}")
        }.onFailure {
            println("DEBUG: Repository.getFlowDrawerImages - Failed to persist snapshot: ${it.message}")
        }
    }

    private fun cleanupReplacedPlaylistFiles(previousAssets: List<Asset>, currentAssets: List<Asset>) {
        val activePaths = currentAssets.map { it.assetUrl }.toSet()
        previousAssets
            .map { it.assetUrl }
            .filter { File(it).isAbsolute }
            .filterNot { activePaths.contains(it) }
            .distinct()
            .forEach { stalePath ->
                runCatching {
                    val staleFile = File(stalePath)
                    if (staleFile.exists() && staleFile.name.startsWith("asset-") && staleFile.delete()) {
                        println("DEBUG: Repository.fetchAssets - Deleted replaced asset file: ${staleFile.name}")
                    }
                }
            }
    }

    fun forceUpdateDefaultAsset(context: Context, onResult: (Boolean) -> Unit) {
        println("DEBUG: Repository.forceUpdateDefaultAsset - Forcing update of default asset to new Cloudinary URL")
        val localFile = File(context.filesDir, "default-asset")

        // Download new default asset
        Thread {
            try {
                val tempFile = File(context.filesDir, "default-asset.tmp")
                if (tempFile.exists()) {
                    tempFile.delete()
                }

                downloadAssetWithRetry(AppConfig.DEFAULT_ASSET_URL, tempFile, 3)
                if (tempFile.exists() && tempFile.length() > 0) {
                    if (localFile.exists() && !localFile.delete()) {
                        println("DEBUG: Repository.forceUpdateDefaultAsset - Failed to delete old default asset before replace")
                    }
                    val replaced = tempFile.renameTo(localFile)
                    if (!replaced) {
                        println("DEBUG: Repository.forceUpdateDefaultAsset - Failed to replace default asset with downloaded file")
                        tempFile.delete()
                        Handler(Looper.getMainLooper()).post { onResult(localFile.exists() && localFile.length() > 0) }
                        return@Thread
                    }
                    println("DEBUG: Repository.forceUpdateDefaultAsset - Successfully downloaded new default asset")
                    Handler(Looper.getMainLooper()).post { onResult(true) }
                } else {
                    println("DEBUG: Repository.forceUpdateDefaultAsset - Failed to download new default asset")
                    tempFile.delete()
                    Handler(Looper.getMainLooper()).post { onResult(localFile.exists() && localFile.length() > 0) }
                }
            } catch (e: Exception) {
                println("DEBUG: Repository.forceUpdateDefaultAsset - Error: ${e.message}")
                val tempFile = File(context.filesDir, "default-asset.tmp")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                Handler(Looper.getMainLooper()).post { onResult(localFile.exists() && localFile.length() > 0) }
            }
        }.start()
    }

    fun clearAllAssetsForFreshDownload(context: Context) {
        try {
            println("DEBUG: Repository.clearAllAssetsForFreshDownload - Clearing all assets for fresh download (except default asset)")
            val filesDir = context.filesDir
            
            // Clear all asset files including temp files, but preserve default-asset
            val allAssetFiles = filesDir.listFiles { _, name -> 
                (name.startsWith("asset-") || name.endsWith(".tmp")) && !name.startsWith("default-asset")
            }
            
            var deletedCount = 0
            if (allAssetFiles != null) {
                for (file in allAssetFiles) {
                    if (file.delete()) {
                        deletedCount++
                        println("DEBUG: Repository.clearAllAssetsForFreshDownload - Deleted: ${file.name}")
                    }
                }
            }
            
            // Also clear assets.json cache to force fresh asset list
            val assetsJsonFile = File(filesDir, "assets.json")
            if (assetsJsonFile.exists()) {
                assetsJsonFile.delete()
                println("DEBUG: Repository.clearAllAssetsForFreshDownload - Deleted assets.json cache")
                deletedCount++
            }
            
            println("DEBUG: Repository.clearAllAssetsForFreshDownload - Cleared $deletedCount files for fresh download (default asset preserved)")
        } catch (e: Exception) {
            println("DEBUG: Repository.clearAllAssetsForFreshDownload - Error: ${e.message}")
            e.printStackTrace()
        }
    }

    fun clearCachedAssets(context: Context) {
        try {
            println("DEBUG: Repository.clearCachedAssets - Clearing ALL cached asset files")
            val filesDir = context.filesDir
            // Clear ALL asset files (including date-suffixed ones)
            val assetFiles = filesDir.listFiles { _, name -> 
                name.startsWith("asset-") // Clear all asset files regardless of suffix
            }
            
            var deletedCount = 0
            if (assetFiles != null) {
                for (file in assetFiles) {
                    if (file.delete()) {
                        deletedCount++
                        println("DEBUG: Repository.clearCachedAssets - Deleted cached file: ${file.name}")
                    }
                }
            }
            
            // Clear assets.json cache file
            val assetsJsonFile = File(filesDir, "assets.json")
            if (assetsJsonFile.exists()) {
                assetsJsonFile.delete()
                println("DEBUG: Repository.clearCachedAssets - Deleted assets.json cache")
            }
            
            println("DEBUG: Repository.clearCachedAssets - Cleared $deletedCount cached asset files")
        } catch (e: Exception) {
            println("DEBUG: Repository.clearCachedAssets - Error clearing cache: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Clean up old assets based on retention period
     * @param context Application context
     * @param retentionDays Number of days to retain assets (default: 30)
     * @return Pair of (deletedCount, errorCount)
     */
    fun cleanupOldAssets(context: Context, retentionDays: Int = 30): Pair<Int, Int> {
        var deletedCount = 0
        var errorCount = 0
        
        try {
            println("DEBUG: Repository.cleanupOldAssets - Starting cleanup with retention: $retentionDays days")
            val filesDir = context.filesDir
            val assetFiles = filesDir.listFiles { _, name -> 
                name.startsWith("asset-") || name == "default-asset"
            }
            
            if (assetFiles == null || assetFiles.isEmpty()) {
                println("DEBUG: Repository.cleanupOldAssets - No asset files found")
                return Pair(0, 0)
            }
            
            val now = System.currentTimeMillis()
            val retentionMs = retentionDays * 24L * 60L * 60L * 1000L // Convert days to milliseconds
            val cutoffTime = now - retentionMs
            
            println("DEBUG: Repository.cleanupOldAssets - Cutoff time: ${java.util.Date(cutoffTime)}")
            
            for (file in assetFiles) {
                try {
                    val lastModified = file.lastModified()
                    val ageDays = (now - lastModified) / (24L * 60L * 60L * 1000L)
                    
                    if (lastModified < cutoffTime) {
                        if (file.delete()) {
                            deletedCount++
                            println("DEBUG: Repository.cleanupOldAssets - Deleted old asset: ${file.name} (age: $ageDays days)")
                        } else {
                            errorCount++
                            println("DEBUG: Repository.cleanupOldAssets - Failed to delete: ${file.name}")
                        }
                    } else {
                        println("DEBUG: Repository.cleanupOldAssets - Keeping asset: ${file.name} (age: $ageDays days)")
                    }
                } catch (e: Exception) {
                    errorCount++
                    println("DEBUG: Repository.cleanupOldAssets - Error processing ${file.name}: ${e.message}")
                }
            }
            
            println("DEBUG: Repository.cleanupOldAssets - Cleanup complete. Deleted: $deletedCount, Errors: $errorCount")
        } catch (e: Exception) {
            println("DEBUG: Repository.cleanupOldAssets - Fatal error: ${e.message}")
            e.printStackTrace()
            errorCount++
        }
        
        return Pair(deletedCount, errorCount)
    }

    fun saveAssetsToFile(context: Context, assets: List<Asset>) {
        val file = File(context.filesDir, "assets.json")
        val json = JSONArray()
        for (asset in assets) {
            val obj = JSONObject()
            obj.put("assetUrl", asset.assetUrl)
            obj.put("slot_number", asset.slotNumber)
            if (asset.originalUrl != null) {
                obj.put("originalUrl", asset.originalUrl)
            }
            if (asset.duration != null) {
                obj.put("duration", asset.duration)
            }
            json.put(obj)
        }
        file.writeText(json.toString())
    }

    fun loadAssetsFromFile(context: Context): List<Asset> {
        val file = File(context.filesDir, "assets.json")
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            if (text.isBlank()) {
                println("DEBUG: Repository.loadAssetsFromFile - assets.json is blank, ignoring cache")
                emptyList()
            } else {
                val arr = JSONArray(text)
                val list = mutableListOf<Asset>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val url = obj.optString("assetUrl", "")
                    val slotNum = obj.optInt("slot_number", 0)
                    val original = if (obj.has("originalUrl")) obj.optString("originalUrl", null) else null
                    val duration = if (obj.has("duration")) obj.optInt("duration", 10) else null
                    if (url.isNotBlank()) {
                        list.add(Asset(url, slotNum, originalUrl = original, duration = duration))
                    }
                }
                list
            }
        } catch (e: Exception) {
            println("DEBUG: Repository.loadAssetsFromFile - Invalid assets.json cache: ${e.message}")
            val deleted = file.delete()
            println("DEBUG: Repository.loadAssetsFromFile - Deleted corrupt assets.json: $deleted")
            emptyList()
        }
    }


    fun updatePlayerStatus(machineId: String, onResult: (Boolean, String?) -> Unit) {
        notifyApiCallStarted()
        val mediaType = "application/json".toMediaType()
        val body = """{
            "machineId":"$machineId"
        }""".toRequestBody(mediaType)

        val request = Request.Builder()
            .url("$BASE_URL/players/update-status")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                Handler(Looper.getMainLooper()).post {
                    onResult(false, "Failed: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                response.use {
                    if (!response.isSuccessful) {
                        Handler(Looper.getMainLooper()).post {
                            onResult(false, "Server error: ${response.code}")
                        }
                        return
                    }
                    Handler(Looper.getMainLooper()).post {
                        onResult(true, null)
                    }
                }
            }
        })
    }

    fun getDefaultAsset(context: Context, screenId: String? = null, onResult: (DefaultAsset?, String?) -> Unit) {
        getDefaultAssetWithRetry(context, screenId, 0, 3, onResult)
    }

    fun checkDefaultAssetUpdate(context: Context, screenId: String? = null, lastUpdate: String?, onResult: (DefaultAsset?, Boolean, String?) -> Unit) {
        checkDefaultAssetUpdateWithRetry(context, screenId, lastUpdate, 0, 3, onResult)
    }

    private fun getDefaultAssetWithRetry(
        context: Context,
        screenId: String?,
        attempt: Int, 
        maxRetries: Int, 
        onResult: (DefaultAsset?, String?) -> Unit
    ) {
        notifyApiCallStarted()
        val url = if (screenId != null) {
            "$BASE_URL/default-asset?screenId=$screenId"
        } else {
            "$BASE_URL/default-asset"
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("ngrok-skip-browser-warning", "true")
            .get()
            .build()

        println("DEBUG: Repository.getDefaultAsset - Attempt ${attempt + 1}/$maxRetries")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                println("DEBUG: Repository.getDefaultAsset - Network failure: ${e.message}")
                
                // Check if we have a cached default asset
                val localFile = File(context.filesDir, "default-asset")
                if (localFile.exists()) {
                    println("DEBUG: Repository.getDefaultAsset - Using cached default asset")
                    // Try to create a default asset from cached file
                    val cachedAsset = DefaultAsset(
                        id = -1,
                        assetUrl = localFile.absolutePath,
                        assetName = "Cached Default Asset",
                        assetType = "image",
                        duration = 10,
                        isActive = true
                    )
                    Handler(Looper.getMainLooper()).post {
                        onResult(cachedAsset, null)
                    }
                    return
                }
                
                // If no cached asset and we have retries left, try again
                if (attempt < maxRetries - 1) {
                    println("DEBUG: Repository.getDefaultAsset - Retrying in ${(attempt + 1) * 2000}ms...")
                    Handler(Looper.getMainLooper()).postDelayed({
                        getDefaultAssetWithRetry(context, screenId, attempt + 1, maxRetries, onResult)
                    }, (attempt + 1) * 2000L)
                } else {
                    // All retries failed, use local default asset from AppConfig
                    println("DEBUG: Repository.getDefaultAsset - All retries failed, using local default asset from AppConfig")
                    val localDefaultAsset = AppConfig.getLocalDefaultAsset()
                    Handler(Looper.getMainLooper()).post {
                        onResult(localDefaultAsset, null)
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                response.use {
                    if (!response.isSuccessful) {
                        println("DEBUG: Repository.getDefaultAsset - Server error: ${response.code}")
                        
                        // Check if we have a cached default asset
                        val localFile = File(context.filesDir, "default-asset")
                        if (localFile.exists()) {
                            println("DEBUG: Repository.getDefaultAsset - Using cached default asset due to server error")
                            val cachedAsset = DefaultAsset(
                                id = -1,
                                assetUrl = localFile.absolutePath,
                                assetName = "Cached Default Asset",
                                assetType = "image",
                                duration = 10,
                                isActive = true
                            )
                            Handler(Looper.getMainLooper()).post {
                                onResult(cachedAsset, null)
                            }
                            return
                        }
                        
                        Handler(Looper.getMainLooper()).post {
                            onResult(null, "Server error: ${response.code}")
                        }
                        return
                    }

                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        println("DEBUG: Repository.getDefaultAsset - Empty response body")
                        
                        // Check if we have a cached default asset
                        val localFile = File(context.filesDir, "default-asset")
                        if (localFile.exists()) {
                            println("DEBUG: Repository.getDefaultAsset - Using cached default asset due to empty response")
                            val cachedAsset = DefaultAsset(
                                id = -1,
                                assetUrl = localFile.absolutePath,
                                assetName = "Cached Default Asset",
                                assetType = "image",
                                duration = 10,
                                isActive = true
                            )
                            Handler(Looper.getMainLooper()).post {
                                onResult(cachedAsset, null)
                            }
                            return
                        }
                        
                        Handler(Looper.getMainLooper()).post {
                            onResult(null, "Empty response")
                        }
                        return
                    }

                    try {
                        println("DEBUG: Repository.getDefaultAsset - Response body: $body")
                        val json = JSONObject(body)
                        println("DEBUG: Repository.getDefaultAsset - Parsed JSON: $json")
                        
                        val success = json.optBoolean("success", false)
                        println("DEBUG: Repository.getDefaultAsset - Success: $success")
                        
                        if (success) {
                            if (!json.has("defaultAsset")) {
                                println("DEBUG: Repository.getDefaultAsset - Missing 'defaultAsset' key in response, using local default asset")
                                val localDefaultAsset = AppConfig.getLocalDefaultAsset()
                                Handler(Looper.getMainLooper()).post {
                                    onResult(localDefaultAsset, null)
                                }
                                return
                            }
                            
                            val assetObj = json.getJSONObject("defaultAsset")
                            println("DEBUG: Repository.getDefaultAsset - Asset object: $assetObj")
                            
                            // Always use our local AppConfig default asset URL instead of server URL
                            // This ensures we use the new Cloudinary image
                            val localDefaultAsset = AppConfig.getLocalDefaultAsset()
                            println("DEBUG: Repository.getDefaultAsset - Overriding server default with local AppConfig: ${localDefaultAsset.assetUrl}")
                            
                            // Download our local default asset with retry
                            val localFile = File(context.filesDir, "default-asset")
                            if (!localFile.exists()) {
                                println("DEBUG: Repository.getDefaultAsset - Downloading local default asset to: ${localFile.absolutePath}")
                                downloadAssetWithRetry(localDefaultAsset.assetUrl, localFile, 3)
                            } else {
                                println("DEBUG: Repository.getDefaultAsset - Local default asset already cached")
                            }
                            
                            Handler(Looper.getMainLooper()).post {
                                onResult(localDefaultAsset, null)
                            }
                        } else {
                            val errorMsg = json.optString("error", "No default asset found")
                            println("DEBUG: Repository.getDefaultAsset - Server returned success=false: $errorMsg, using local default asset")
                            val localDefaultAsset = AppConfig.getLocalDefaultAsset()
                            Handler(Looper.getMainLooper()).post {
                                onResult(localDefaultAsset, null)
                            }
                        }
                    } catch (e: Exception) {
                        println("DEBUG: Repository.getDefaultAsset - Parse error: ${e.message}")
                        e.printStackTrace()
                        
                        // Check if we have a cached default asset, if not use AppConfig
                        val localFile = File(context.filesDir, "default-asset")
                        if (localFile.exists()) {
                            println("DEBUG: Repository.getDefaultAsset - Using cached default asset due to parse error")
                            val cachedAsset = DefaultAsset(
                                id = -1,
                                assetUrl = localFile.absolutePath,
                                assetName = "Cached Default Asset",
                                assetType = "image",
                                duration = 10,
                                isActive = true
                            )
                            Handler(Looper.getMainLooper()).post {
                                onResult(cachedAsset, null)
                            }
                        } else {
                            println("DEBUG: Repository.getDefaultAsset - No cached asset, using AppConfig default")
                            val localDefaultAsset = AppConfig.getLocalDefaultAsset()
                            Handler(Looper.getMainLooper()).post {
                                onResult(localDefaultAsset, "Parse error and no cached asset")
                            }
                        }
                    }
                }
            }
        })
    }

    private fun checkDefaultAssetUpdateWithRetry(
        context: Context,
        screenId: String?,
        lastUpdate: String?,
        attempt: Int,
        maxRetries: Int,
        onResult: (DefaultAsset?, Boolean, String?) -> Unit
    ) {
        notifyApiCallStarted()
        val baseUrl = if (screenId != null) {
            "$BASE_URL/default-asset/check-update?screenId=$screenId"
        } else {
            "$BASE_URL/default-asset/check-update"
        }
        val url = if (lastUpdate != null) {
            "$baseUrl&lastUpdate=${java.net.URLEncoder.encode(lastUpdate, "UTF-8")}"
        } else {
            baseUrl
        }
        
        val request = Request.Builder()
            .url(url)
            .addHeader("ngrok-skip-browser-warning", "true")
            .get()
            .build()

        println("DEBUG: Repository.checkDefaultAssetUpdate - URL: $url (attempt ${attempt + 1}/$maxRetries)")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                println("DEBUG: Repository.checkDefaultAssetUpdate - Network failure: ${e.message}")
                
                // Check if we have a cached default asset
                val localFile = File(context.filesDir, "default-asset")
                if (localFile.exists()) {
                    println("DEBUG: Repository.checkDefaultAssetUpdate - Using cached default asset")
                    val cachedAsset = DefaultAsset(
                        id = -1,
                        assetUrl = localFile.absolutePath,
                        assetName = "Cached Default Asset",
                        assetType = "image",
                        duration = 10,
                        isActive = true
                    )
                    Handler(Looper.getMainLooper()).post {
                        onResult(cachedAsset, false, null)
                    }
                    return
                }
                
                // If no cached asset and we have retries left, try again
                if (attempt < maxRetries - 1) {
                    println("DEBUG: Repository.checkDefaultAssetUpdate - Retrying in ${(attempt + 1) * 2000}ms...")
                    Handler(Looper.getMainLooper()).postDelayed({
                        checkDefaultAssetUpdateWithRetry(context, screenId, lastUpdate, attempt + 1, maxRetries, onResult)
                    }, (attempt + 1) * 2000L)
                } else {
                    Handler(Looper.getMainLooper()).post {
                        onResult(null, false, "Failed: ${e.message}")
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                response.use {
                    if (!response.isSuccessful) {
                        println("DEBUG: Repository.checkDefaultAssetUpdate - Server error: ${response.code}")
                        
                        // Check if we have a cached default asset
                        val localFile = File(context.filesDir, "default-asset")
                        if (localFile.exists()) {
                            println("DEBUG: Repository.checkDefaultAssetUpdate - Using cached default asset due to server error")
                            val cachedAsset = DefaultAsset(
                                id = -1,
                                assetUrl = localFile.absolutePath,
                                assetName = "Cached Default Asset",
                                assetType = "image",
                                duration = 10,
                                isActive = true
                            )
                            Handler(Looper.getMainLooper()).post {
                                onResult(cachedAsset, false, null)
                            }
                            return
                        }
                        
                        Handler(Looper.getMainLooper()).post {
                            onResult(null, false, "Server error: ${response.code}")
                        }
                        return
                    }

                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        println("DEBUG: Repository.checkDefaultAssetUpdate - Empty response body")
                        
                        // Check if we have a cached default asset
                        val localFile = File(context.filesDir, "default-asset")
                        if (localFile.exists()) {
                            println("DEBUG: Repository.checkDefaultAssetUpdate - Using cached default asset due to empty response")
                            val cachedAsset = DefaultAsset(
                                id = -1,
                                assetUrl = localFile.absolutePath,
                                assetName = "Cached Default Asset",
                                assetType = "image",
                                duration = 10,
                                isActive = true
                            )
                            Handler(Looper.getMainLooper()).post {
                                onResult(cachedAsset, false, null)
                            }
                            return
                        }
                        
                        Handler(Looper.getMainLooper()).post {
                            onResult(null, false, "Empty response")
                        }
                        return
                    }

                    try {
                        val json = JSONObject(body)
                        if (json.getBoolean("success")) {
                            val hasUpdate = json.getBoolean("hasUpdate")
                            
                            if (hasUpdate && json.has("defaultAsset")) {
                                val assetObj = json.getJSONObject("defaultAsset")
                                val asset = DefaultAsset(
                                    id = assetObj.getInt("id"),
                                    assetUrl = assetObj.getString("assetUrl"),
                                    assetName = assetObj.optString("assetName", null),
                                    assetType = assetObj.getString("assetType"),
                                    duration = assetObj.getInt("duration"),
                                    isActive = assetObj.getBoolean("isActive")
                                )
                                
                                // Download updated default asset
                                val localFile = File(context.filesDir, "default-asset")
                                downloadAssetWithRetry(asset.assetUrl, localFile, 3)
                                
                                Handler(Looper.getMainLooper()).post {
                                    onResult(asset, true, null)
                                }
                            } else {
                                // No update available, use cached asset
                                val localFile = File(context.filesDir, "default-asset")
                                if (localFile.exists()) {
                                    val cachedAsset = DefaultAsset(
                                        id = -1,
                                        assetUrl = localFile.absolutePath,
                                        assetName = "Cached Default Asset",
                                        assetType = "image",
                                        duration = 10,
                                        isActive = true
                                    )
                                    Handler(Looper.getMainLooper()).post {
                                        onResult(cachedAsset, false, null)
                                    }
                                } else {
                                    Handler(Looper.getMainLooper()).post {
                                        onResult(null, false, "No default asset available")
                                    }
                                }
                            }
                        } else {
                            Handler(Looper.getMainLooper()).post {
                                onResult(null, false, "No default asset found")
                            }
                        }
                    } catch (e: Exception) {
                        println("DEBUG: Repository.checkDefaultAssetUpdate - Parse error: ${e.message}")
                        
                        // Check if we have a cached default asset
                        val localFile = File(context.filesDir, "default-asset")
                        if (localFile.exists()) {
                            println("DEBUG: Repository.checkDefaultAssetUpdate - Using cached default asset due to parse error")
                            val cachedAsset = DefaultAsset(
                                id = -1,
                                assetUrl = localFile.absolutePath,
                                assetName = "Cached Default Asset",
                                assetType = "image",
                                duration = 10,
                                isActive = true
                            )
                            Handler(Looper.getMainLooper()).post {
                                onResult(cachedAsset, false, null)
                            }
                        } else {
                            Handler(Looper.getMainLooper()).post {
                                onResult(null, false, "Parse error: ${e.message}")
                            }
                        }
                    }
                }
            }
        })
    }
    
    // Adscape Player Registration
    fun registerAdscapePlayer(
        context: Context,
        screenId: String,
        appVersion: String = "F3",
        flowType: String? = null,
        deviceName: String? = null,
        screenWidth: Int? = null,
        screenHeight: Int? = null,
        ipAddress: String? = null,
        location: String? = null,
        osVersion: String? = null,
        appVersionCode: String? = null,
        callback: (Boolean, String?, String?, String?) -> Unit
    ) {
        notifyApiCallStarted()
        
        // Always send all fields, even if null (let server handle nulls)
        val json = JSONObject().apply {
            put("screenId", screenId)
            put("appVersion", appVersion)
            if (flowType != null) put("flowType", flowType)
            put("deviceName", deviceName ?: JSONObject.NULL)
            if (screenWidth != null) put("screenWidth", screenWidth)
            if (screenHeight != null) put("screenHeight", screenHeight)
            put("ipAddress", ipAddress ?: JSONObject.NULL)
            put("location", location ?: JSONObject.NULL)
            put("osVersion", osVersion ?: JSONObject.NULL)
            put("appVersionCode", appVersionCode ?: JSONObject.NULL)
        }
        
        println("DEBUG: Repository.registerAdscapePlayer - Sending registration: $json")
        
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$BASE_URL/adscape/register")
            .post(requestBody)
            .addHeader("ngrok-skip-browser-warning", "true")
            .addHeader("Content-Type", "application/json")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                println("DEBUG: Repository.registerAdscapePlayer - Request failed: ${e.message}")
                callback(false, null, null, e.message)
            }
            
            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                val responseBody = response.body?.string()
                println("DEBUG: Repository.registerAdscapePlayer - Response code: ${response.code}, body: $responseBody")
                
                if (response.isSuccessful) {
                    try {
                        val jsonResponse = JSONObject(responseBody ?: "{}")
                        val ok = jsonResponse.optBoolean("ok", false)
                        if (ok) {
                            val player = jsonResponse.optJSONObject("player")
                            val returnedFlowType = player?.optString("flowType")
                            val registrationCode = player?.optString("registrationCode")
                            println("DEBUG: Repository.registerAdscapePlayer - Server returned flow type: '$returnedFlowType', registrationCode: '$registrationCode'")
                            callback(true, returnedFlowType, registrationCode, null)
                        } else {
                            val error = jsonResponse.optString("error", "Registration failed")
                            println("DEBUG: Repository.registerAdscapePlayer - Registration failed: $error")
                            callback(false, null, null, error)
                        }
                    } catch (e: Exception) {
                        println("DEBUG: Repository.registerAdscapePlayer - JSON parse error: ${e.message}")
                        callback(false, null, null, "JSON parse error: ${e.message}")
                    }
                } else {
                    println("DEBUG: Repository.registerAdscapePlayer - HTTP error ${response.code}: $responseBody")
                    callback(false, null, null, "HTTP ${response.code}: $responseBody")
                }
            }
        })
    }
    
    // Get Adscape Player Flow Type
    fun getAdscapePlayerInfo(
        screenId: String,
        context: Context? = null,
        onResult: (Boolean, String?, String?, String?, String?, Boolean?, Float?, Boolean?) -> Unit
    ) {
        notifyApiCallStarted()
        val url = "$BASE_URL/adscape/player/$screenId"
        println("DEBUG: Repository.getAdscapePlayerInfo - URL: $url")
        
        val request = Request.Builder()
            .url(url)
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                        println("DEBUG: Repository.getAdscapePlayerInfo - Network failure: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    onResult(false, null, null, null, null, null, null, null)
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                response.use {
                    try {
                        val body = response.body?.string()
                        println("DEBUG: Repository.getAdscapePlayerInfo - Response code: ${response.code}, body length: ${body?.length}")
                        
                        if (!response.isSuccessful || body == null) {
                            println("DEBUG: Repository.getAdscapePlayerInfo - Server error: ${response.code}")
                            Handler(Looper.getMainLooper()).post {
                                onResult(false, null, null, null, null, null, null, null)
                            }
                            return
                        }
                        
                        val json = JSONObject(body)
                        if (json.optBoolean("ok", false)) {
                            val player = json.optJSONObject("player")
                            if (player != null) {
                                // Extract playlistId - handle null, empty string, or string "null"
                                val playlistIdRaw = if (player.isNull("playlistId")) null else player.optString("playlistId", null)
                                val playlistId = if (playlistIdRaw == null || playlistIdRaw.isBlank() || playlistIdRaw == "null") null else playlistIdRaw
                                val playlistStartDate = player.optString("playlistStartDate", null)
                                val playlistEndDate = player.optString("playlistEndDate", null)
                                val flowType = player.optString("flowType", null)
                                val isEnabled = player.optBoolean("isEnabled", true)
                                val heightCalibration = player.optDouble("heightCalibration", 0.0).toFloat()
                                val heightCalibrationEnabled = player.optBoolean("heightCalibrationEnabled", true)
                                val flowDrawerEnabled = player.optBoolean("flowDrawerEnabled", true)
                                val flowDrawerSlotCount = if (player.has("flowDrawerSlotCount") && !player.isNull("flowDrawerSlotCount")) {
                                    player.optInt("flowDrawerSlotCount", 2)
                                } else null
                                val hideScreenId = player.optBoolean("hideScreenId", false)
                                println("DEBUG: Repository.getAdscapePlayerInfo - Parsed hideScreenId from API: $hideScreenId")
                                
                                // Parse admin-controlled app mode (F1 / F2 / PlayerOnly)
                                val appMode = player.optString("appMode", null)
                                    ?.takeIf { it.isNotBlank() }

                                // Parse admin-controlled rotation
                                val rotation = player.optString("rotation", "portrait")
                                    ?.takeIf { it.isNotBlank() } ?: "portrait"
                                
                                // Extract additional fields for persistent storage
                                val deviceName = player.optString("deviceName", null)
                                val location = player.optString("location", null)
                                val paymentAmount = if (player.has("paymentAmount") && !player.isNull("paymentAmount")) {
                                    try {
                                        val value = player.getDouble("paymentAmount")
                                        if (!value.isNaN()) value else null
                                    } catch (e: Exception) {
                                        null
                                    }
                                } else null
                                
                                // Save to info.json if context is provided
                                context?.let { ctx ->
                                    val screenInfo = InfoStorage.ScreenInfo(
                                        screenId = screenId,
                                        screenName = deviceName,
                                        location = location,
                                        heightCalibration = heightCalibration,
                                        heightCalibrationEnabled = heightCalibrationEnabled,
                                        playlistId = playlistId, // This will be null if no playlist assigned
                                        paymentAmount = paymentAmount,
                                        flowDrawerEnabled = flowDrawerEnabled,
                                        flowDrawerSlotCount = flowDrawerSlotCount,
                                        hideScreenId = hideScreenId,
                                        appMode = appMode,
                                        rotation = rotation
                                    )
                                    InfoStorage.saveInfo(ctx, screenInfo)
                                    println("DEBUG: Repository.getAdscapePlayerInfo - Saved to info.json: screenName=$deviceName, location=$location, heightCalibrationEnabled=$heightCalibrationEnabled, flowDrawerEnabled=$flowDrawerEnabled, flowDrawerSlotCount=$flowDrawerSlotCount, hideScreenId=$hideScreenId, playlistId=$playlistId, paymentAmount=$paymentAmount, appMode=$appMode, rotation=$rotation")
                                    
                                    // If playlistId is null, immediately clear cached assets to stop playback
                                    if (playlistId == null) {
                                        println("DEBUG: Repository.getAdscapePlayerInfo - PlaylistId is null, clearing cached assets")
                                        clearCachedAssets(ctx)
                                    }
                                }
                                
                                println("DEBUG: Repository.getAdscapePlayerInfo - Success: playlistId=$playlistId, startDate=$playlistStartDate, endDate=$playlistEndDate, isEnabled=$isEnabled, heightCalibration=$heightCalibration, heightCalibrationEnabled=$heightCalibrationEnabled, deviceName=$deviceName, location=$location, paymentAmount=$paymentAmount")
                                Handler(Looper.getMainLooper()).post {
                                    onResult(true, playlistId, playlistStartDate, playlistEndDate, flowType, isEnabled, heightCalibration, heightCalibrationEnabled)
                                }
                            } else {
                                Handler(Looper.getMainLooper()).post {
                                    onResult(false, null, null, null, null, null, null, null)
                                }
                            }
                        } else {
                            Handler(Looper.getMainLooper()).post {
                                onResult(false, null, null, null, null, null, null, null)
                            }
                        }
                    } catch (e: Exception) {
                        println("DEBUG: Repository.getAdscapePlayerInfo - Parse error: ${e.message}")
                        Handler(Looper.getMainLooper()).post {
                            onResult(false, null, null, null, null, null, null, null)
                        }
                    }
                }
            }
        })
    }

    fun getAdscapePlayerFlowType(
        screenId: String,
        callback: (Boolean, String?, String?) -> Unit
    ) {
        notifyApiCallStarted()
        
        val request = Request.Builder()
            .url("$BASE_URL/adscape/player/$screenId")
            .get()
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                callback(false, null, e.message)
            }
            
            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                val responseBody = response.body?.string()
                
                if (response.isSuccessful) {
                    try {
                        val jsonResponse = JSONObject(responseBody ?: "{}")
                        val ok = jsonResponse.optBoolean("ok", false)
                        if (ok) {
                            val player = jsonResponse.optJSONObject("player")
                            val flowType = player?.optString("flowType")
                            callback(true, flowType, null)
                        } else {
                            callback(false, null, "Player not found")
                        }
                    } catch (e: Exception) {
                        callback(false, null, "JSON parse error: ${e.message}")
                    }
                } else {
                    callback(false, null, "HTTP ${response.code}: $responseBody")
                }
            }
        })
    }
    
    // Get BMI record by ID
    fun getBMIRecord(
        bmiId: String,
        callback: (Boolean, Map<String, Any>?, String?) -> Unit
    ) {
        notifyApiCallStarted()
        val url = "$BASE_URL/bmi/$bmiId"
        println("DEBUG: Repository.getBMIRecord - URL: $url")
        
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                println("DEBUG: Repository.getBMIRecord - Network failure: ${e.message}")
                callback(false, null, e.message)
            }
            
            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                val responseBody = response.body?.string()
                println("DEBUG: Repository.getBMIRecord - Response code: ${response.code}, body length: ${responseBody?.length}")
                
                if (response.isSuccessful) {
                    try {
                        val jsonResponse = JSONObject(responseBody ?: "{}")
                        val payload = jsonResponse.optJSONObject("payload") ?: jsonResponse
                        val bmiData = mutableMapOf<String, Any>()
                        val keys = payload.keys()
                        while (keys.hasNext()) {
                            val key: String = keys.next() as String
                            val value: Any? = payload.get(key)
                            val convertedValue: Any = when (value) {
                                is JSONObject -> {
                                    val nestedMap = mutableMapOf<String, Any>()
                                    val nestedKeys = value.keys()
                                    while (nestedKeys.hasNext()) {
                                        val nestedKey: String = nestedKeys.next() as String
                                        val nestedValue: Any? = value.get(nestedKey)
                                        nestedMap[nestedKey] = nestedValue ?: ""
                                    }
                                    nestedMap
                                }
                                is org.json.JSONArray -> value.toString()
                                null -> ""
                                else -> value as Any
                            }
                            bmiData[key] = convertedValue
                        }
                        if (bmiData.containsKey("fortune") && !bmiData.containsKey("fortuneMessage")) {
                            bmiData["fortune"]?.let { bmiData["fortuneMessage"] = it }
                        }
                        println("DEBUG: Repository.getBMIRecord - Success: ${bmiData.keys.joinToString(", ")}")
                        callback(true, bmiData, null)
                    } catch (e: Exception) {
                        println("DEBUG: Repository.getBMIRecord - Parse error: ${e.message}")
                        callback(false, null, "Parse error: ${e.message}")
                    }
                } else {
                    callback(false, null, "HTTP ${response.code}: $responseBody")
                }
            }
        })
    }
    
    // Create BMI record and get webUrl. Optionally send locally-picked fortune and healthTip to be stored in DB.
    fun createBMI(
        heightCm: Float?,
        weightKg: Float,
        screenId: String,
        appVersion: String,
        fortune: String? = null,
        healthTip: String? = null,
        callback: (Boolean, String?, String?, String?) -> Unit // success, webUrl, bmiId, error
    ) {
        notifyApiCallStarted()
        
        // ========== PAYMENT FLOW LOGGING - API CALL START ==========
        println("[PAYMENT_FLOW] 📤 Repository.createBMI - Sending request to server")
        println("[PAYMENT_FLOW] URL: $BASE_URL/bmi")
        println("[PAYMENT_FLOW] Payload: heightCm=$heightCm, weightKg=$weightKg, screenId=$screenId, appVersion=$appVersion, fortune=${!fortune.isNullOrBlank()}, healthTip=${!healthTip.isNullOrBlank()}")
        
        val json = JSONObject().apply {
            put("weightKg", weightKg)
            put("screenId", screenId)
            put("appVersion", appVersion)
            if (heightCm != null && heightCm > 0f) {
                put("heightCm", heightCm)
            }
            if (!fortune.isNullOrBlank()) put("fortune", fortune)
            if (!healthTip.isNullOrBlank()) put("healthTip", healthTip)
        }
        
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$BASE_URL/bmi")
            .post(requestBody)
            .addHeader("ngrok-skip-browser-warning", "true")
            .addHeader("Content-Type", "application/json")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                println("[PAYMENT_FLOW] ❌ Repository.createBMI - Network failure: ${e.message}")
                callback(false, null, null, e.message)
            }
            
            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                val responseBody = response.body?.string()
                
                // ========== PAYMENT FLOW LOGGING - API RESPONSE ==========
                println("[PAYMENT_FLOW] 📥 Repository.createBMI - Response received")
                println("[PAYMENT_FLOW] Status Code: ${response.code}")
                println("[PAYMENT_FLOW] Response Body: $responseBody")
                
                if (response.isSuccessful) {
                    try {
                        val jsonResponse = JSONObject(responseBody ?: "{}")
                        val ok = jsonResponse.optBoolean("ok", false)
                        if (ok) {
                            val webUrl = jsonResponse.optString("webUrl", null)
                            val bmiId = jsonResponse.optString("bmiId", null)
                            println("[PAYMENT_FLOW] ✅ Repository.createBMI - Success")
                            println("[PAYMENT_FLOW] BMI ID: $bmiId")
                            println("[PAYMENT_FLOW] Web URL: $webUrl")
                            callback(true, webUrl, bmiId, null)
                        } else {
                            val error = jsonResponse.optString("error", "BMI creation failed")
                            println("[PAYMENT_FLOW] ❌ Repository.createBMI - Server error: $error")
                            callback(false, null, null, error)
                        }
                    } catch (e: Exception) {
                        println("[PAYMENT_FLOW] ❌ Repository.createBMI - JSON parse error: ${e.message}")
                        callback(false, null, null, "JSON parse error: ${e.message}")
                    }
                } else {
                    println("[PAYMENT_FLOW] ❌ Repository.createBMI - HTTP error ${response.code}: $responseBody")
                    callback(false, null, null, "HTTP ${response.code}: $responseBody")
                }
            }
        })
    }

    suspend fun saveBmiToLocal(
        context: Context,
        userId: String?,
        weight: Double,
        height: Double,
        bmi: Double,
        category: String,
        screenId: String
    ) {
        withContext(Dispatchers.IO) {
            val database = AppDatabase.getDatabase(context)
            val entity = BmiEntity(
                userId = userId,
                weight = weight,
                height = height,
                bmi = bmi,
                category = category,
                screenId = screenId
            )
            database.bmiDao().insert(entity)
            println("DEBUG: Repository.saveBmiToLocal - Saved BMI record for offline sync")
            
            // Schedule sync worker
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val syncRequest = OneTimeWorkRequestBuilder<BmiSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            
            WorkManager.getInstance(context).enqueueUniqueWork(
                "bmi_sync",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                syncRequest
            )
        }
    }

    suspend fun createBMI(
        userId: String?,
        weight: Double,
        height: Double,
        bmi: Double,
        category: String,
        screenId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("userId", userId)
                put("weightKg", weight)
                put("heightCm", height)
                put("screenId", screenId)
                put("bmi", bmi)
                put("category", category)
                put("timestamp", System.currentTimeMillis())
            }

            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/bmi/sync")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            println("ERROR: Repository.createBMI (sync) - ${e.message}")
            false
        }
    }
    
    // Check if screen is registered and enabled
    fun checkScreenStatus(
        screenId: String,
        callback: (Boolean, Boolean, String?) -> Unit // (isRegistered, isEnabled, error)
    ) {
        notifyApiCallStarted()
        
        val request = Request.Builder()
            .url("$BASE_URL/adscape/player/$screenId")
            .get()
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                callback(false, false, e.message)
            }
            
            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                val responseBody = response.body?.string()
                
                if (response.isSuccessful) {
                    try {
                        val jsonResponse = JSONObject(responseBody ?: "{}")
                        val ok = jsonResponse.optBoolean("ok", false)
                        if (ok) {
                            val player = jsonResponse.optJSONObject("player")
                            val isEnabled = player?.optBoolean("isEnabled", true) ?: true
                            callback(true, isEnabled, null)
                        } else {
                            // Screen not found = not registered
                            callback(false, false, null)
                        }
                    } catch (e: Exception) {
                        callback(false, false, "JSON parse error: ${e.message}")
                    }
                } else if (response.code == 404) {
                    // Screen not found = not registered
                    callback(false, false, null)
                } else {
                    callback(false, false, "HTTP ${response.code}: $responseBody")
                }
            }
        })
    }
    
    // Get logo URL for screen and download to local storage
    fun getScreenLogo(
        context: Context,
        screenId: String,
        callback: (Boolean, String?, String?) -> Unit // success, localLogoPath, error
    ) {
        notifyApiCallStarted()
        
        val request = Request.Builder()
            .url("$BASE_URL/adscape/player/$screenId/logo")
            .get()
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                // Try to use cached logo on network failure
                val localFile = File(context.filesDir, "screen-logo-$screenId")
                if (localFile.exists()) {
                    println("DEBUG: Repository.getScreenLogo - Network failure, using cached logo")
                    callback(true, localFile.absolutePath, null)
                } else {
                    callback(false, null, e.message)
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                val responseBody = response.body?.string()
                
                if (response.isSuccessful) {
                    try {
                        val jsonResponse = JSONObject(responseBody ?: "{}")
                        val ok = jsonResponse.optBoolean("ok", false)
                        if (ok) {
                            val logoUrl = jsonResponse.optString("logoUrl", null)
                            if (!logoUrl.isNullOrBlank()) {
                                // Download logo to local storage
                                val localFile = File(context.filesDir, "screen-logo-$screenId")
                                
                                // Check if we need to re-download: check stored URL in SharedPreferences
                                val prefs = context.getSharedPreferences("screen_logo_prefs", Context.MODE_PRIVATE)
                                val cachedLogoUrl = prefs.getString("logo_url_$screenId", null)
                                val needsDownload = !localFile.exists() || cachedLogoUrl != logoUrl
                                
                                if (needsDownload) {
                                    // Delete old file if URL changed
                                    if (localFile.exists() && cachedLogoUrl != null && cachedLogoUrl != logoUrl) {
                                        localFile.delete()
                                        println("DEBUG: Repository.getScreenLogo - Logo URL changed, deleting old cached file")
                                    }
                                    
                                    // Download logo
                                    try {
                                        downloadAssetWithRetry(logoUrl, localFile, 3)
                                        
                                        // Save logo URL to SharedPreferences
                                        prefs.edit().putString("logo_url_$screenId", logoUrl).apply()
                                        
                                        println("DEBUG: Repository.getScreenLogo - Logo downloaded to ${localFile.absolutePath}")
                                        callback(true, localFile.absolutePath, null)
                                    } catch (e: Exception) {
                                        println("DEBUG: Repository.getScreenLogo - Failed to download logo: ${e.message}")
                                        // If download fails but cached file exists, use cached
                                        if (localFile.exists()) {
                                            callback(true, localFile.absolutePath, null)
                                        } else {
                                            callback(false, null, "Failed to download logo: ${e.message}")
                                        }
                                    }
                                } else {
                                    // Use cached file
                                    println("DEBUG: Repository.getScreenLogo - Using cached logo from ${localFile.absolutePath}")
                                    callback(true, localFile.absolutePath, null)
                                }
                            } else {
                                // No logo URL - delete cached file if exists
                                val localFile = File(context.filesDir, "screen-logo-$screenId")
                                if (localFile.exists()) {
                                    localFile.delete()
                                    val prefs = context.getSharedPreferences("screen_logo_prefs", Context.MODE_PRIVATE)
                                    prefs.edit().remove("logo_url_$screenId").apply()
                                }
                                callback(true, null, null)
                            }
                        } else {
                            // Logo not found - delete cached file if exists
                            val localFile = File(context.filesDir, "screen-logo-$screenId")
                            if (localFile.exists()) {
                                localFile.delete()
                                val prefs = context.getSharedPreferences("screen_logo_prefs", Context.MODE_PRIVATE)
                                prefs.edit().remove("logo_url_$screenId").apply()
                            }
                            callback(true, null, null)
                        }
                    } catch (e: Exception) {
                        callback(false, null, "JSON parse error: ${e.message}")
                    }
                } else if (response.code == 404) {
                    // Logo not found - delete cached file if exists
                    val localFile = File(context.filesDir, "screen-logo-$screenId")
                    if (localFile.exists()) {
                        localFile.delete()
                        val prefs = context.getSharedPreferences("screen_logo_prefs", Context.MODE_PRIVATE)
                        prefs.edit().remove("logo_url_$screenId").apply()
                    }
                    callback(true, null, null)
                } else {
                    // Server error - try to use cached logo
                    val localFile = File(context.filesDir, "screen-logo-$screenId")
                    if (localFile.exists()) {
                        println("DEBUG: Repository.getScreenLogo - Server error, using cached logo")
                        callback(true, localFile.absolutePath, null)
                    } else {
                        callback(false, null, "HTTP ${response.code}: $responseBody")
                    }
                }
            }
        })
    }
    
    // Get flow drawer images for screen and download to local storage
    fun getFlowDrawerImages(
        context: Context,
        screenId: String,
        callback: (Boolean, List<String>?, String?) -> Unit // success, imagePaths list, error
    ) {
        notifyApiCallStarted()
        
        // Step 1: Try the specific flow-drawer-images endpoint first
        val specificUrl = "$BASE_URL/adscape/player/$screenId/flow-drawer-images"
        println("DEBUG: Repository.getFlowDrawerImages - Trying specific endpoint: $specificUrl")
        
        val request = Request.Builder()
            .url(specificUrl)
            .get()
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                println("DEBUG: Repository.getFlowDrawerImages - Specific endpoint failed: ${e.message}")
                // Try fallback to general assets endpoint
                fetchFlowDrawerFromGeneralAssets(context, screenId, callback)
            }
            
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                
                if (response.isSuccessful && !responseBody.isNullOrBlank()) {
                    try {
                        val jsonResponse = JSONObject(responseBody)
                        val ok = jsonResponse.optBoolean("ok", false)
                        println(
                            "DEBUG: Repository.getFlowDrawerImages - Specific endpoint payload: " +
                                "slotCount=${jsonResponse.optInt("slotCount", 0)}, " +
                                "slot1=${jsonResponse.optString("flowDrawerImage1Url", "null")}, " +
                                "slot2=${jsonResponse.optString("flowDrawerImage2Url", "null")}, " +
                                "slot3=${jsonResponse.optString("flowDrawerImage3Url", "null")}"
                        )
                        if (ok && ((jsonResponse.has("imageUrls") && jsonResponse.optJSONArray("imageUrls") != null) || 
                                   (jsonResponse.optString("flowDrawerImage1Url", "").isNotBlank() || 
                                    jsonResponse.optString("flowDrawerImage2Url", "").isNotBlank() || 
                                    jsonResponse.optString("flowDrawerImage3Url", "").isNotBlank()))) {
                            println("DEBUG: Repository.getFlowDrawerImages - Specific endpoint returned valid data")
                            processFlowDrawerJsonResponse(context, screenId, jsonResponse, callback)
                        } else {
                            println("DEBUG: Repository.getFlowDrawerImages - Specific endpoint returned ok=false or no images, trying general assets")
                            fetchFlowDrawerFromGeneralAssets(context, screenId, callback)
                        }
                    } catch (e: Exception) {
                        println("DEBUG: Repository.getFlowDrawerImages - Parse error on specific endpoint: ${e.message}")
                        fetchFlowDrawerFromGeneralAssets(context, screenId, callback)
                    }
                } else {
                    println("DEBUG: Repository.getFlowDrawerImages - Specific endpoint unsuccessful: ${response.code}")
                    fetchFlowDrawerFromGeneralAssets(context, screenId, callback)
                }
            }
        })
    }

    private fun fetchFlowDrawerFromGeneralAssets(
        context: Context,
        screenId: String,
        callback: (Boolean, List<String>?, String?) -> Unit
    ) {
        val generalUrl = "$BASE_URL/assets/$screenId"
        println("DEBUG: Repository.getFlowDrawerImages - Falling back to general assets endpoint: $generalUrl")
        
        val request = Request.Builder()
            .url(generalUrl)
            .get()
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                println("DEBUG: Repository.getFlowDrawerImages - General assets fallback failed: ${e.message}")
                // On total failure, try to use whatever is cached
                returnCachedFlowDrawerImages(context, screenId, callback)
            }
            
            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                val responseBody = response.body?.string()
                
                if (response.isSuccessful && !responseBody.isNullOrBlank()) {
                    try {
                        val jsonArray = JSONArray(responseBody)
                        println("DEBUG: Repository.getFlowDrawerImages - Parsing ${jsonArray.length()} general assets for flow drawer items")
                        
                        // Map assets to slots (101-110 -> 1-10, or type "f1"/"f3")
                        val slots = mutableMapOf<Int, String>()
                        for (i in 0 until jsonArray.length()) {
                            val asset = jsonArray.getJSONObject(i)
                            val url = asset.optString("asset_url", "")
                            val slotNum = if (asset.has("slot_number")) asset.optInt("slot_number", 0) else asset.optInt("slotNumber", 0)
                            val type = asset.optString("type", "").lowercase()
                            
                            // Heuristics for flow drawer assets:
                            // 1. Type is "f1" or "f3" or "flow-drawer"
                            // 2. Slot number is in range 101-110 (F1) or 301-310 (F3)
                            
                            var targetSlot: Int? = null
                            if (type == "f1" || (slotNum in 101..110)) {
                                targetSlot = if (slotNum >= 101) slotNum - 100 else slotNum
                            } else if (type == "f3" || (slotNum in 301..310)) {
                                targetSlot = if (slotNum >= 301) slotNum - 300 else slotNum
                            } else if (type == "flow-drawer") {
                                targetSlot = slotNum
                            }
                            
                            val normalizedUrl = normalizeServerAssetUrl(url)
                            if (targetSlot != null && normalizedUrl != null) {
                                slots[targetSlot] = normalizedUrl
                                println("DEBUG: Repository.getFlowDrawerImages - Found drawer asset: $normalizedUrl at slot $targetSlot (original slot $slotNum, type $type)")
                            }
                        }
                        
                        if (slots.isNotEmpty()) {
                            // Mock a JSON response structure to use existing processing logic
                            val mockResponse = JSONObject().apply {
                                put("ok", true)
                                put("slotCount", slots.keys.maxOrNull() ?: 2)
                                slots.forEach { (slot, url) ->
                                    put("flowDrawerImage${slot}Url", url)
                                }
                            }
                            processFlowDrawerJsonResponse(context, screenId, mockResponse, callback)
                        } else {
                            println("DEBUG: Repository.getFlowDrawerImages - No flow drawer assets found in general list")
                            returnCachedFlowDrawerImages(context, screenId, callback)
                        }
                    } catch (e: Exception) {
                        println("DEBUG: Repository.getFlowDrawerImages - Parse error on general assets: ${e.message}")
                        returnCachedFlowDrawerImages(context, screenId, callback)
                    }
                } else {
                    returnCachedFlowDrawerImages(context, screenId, callback)
                }
            }
        })
    }

    private fun returnCachedFlowDrawerImages(
        context: Context,
        screenId: String,
        callback: (Boolean, List<String>?, String?) -> Unit
    ) {
        val flowDrawerDir = File(context.filesDir, "flow_drawer_images_$screenId")
        val configuredSlotCount = InfoStorage.getFlowDrawerSlotCount(context)?.coerceAtLeast(1) ?: 2
        val imagePaths = MutableList(configuredSlotCount) { "" }
        if (flowDrawerDir.exists()) {
            flowDrawerDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                val slotNumber = extractFlowDrawerSlotNumber(file.name)
                if (
                    slotNumber != null &&
                    slotNumber in 1..configuredSlotCount &&
                    file.length() > 0 &&
                    !file.name.endsWith(".tmp", ignoreCase = true)
                ) {
                    imagePaths[slotNumber - 1] = file.absolutePath
                }
            }
        }
        println("DEBUG: Repository.getFlowDrawerImages - Returning ${imagePaths.count { it.isNotBlank() }}/$configuredSlotCount cached images")
        persistFlowDrawerSnapshot(
            context = context,
            screenId = screenId,
            slotCount = configuredSlotCount,
            slotUrls = imagePaths.map { null },
            localPathsBySlot = imagePaths.mapIndexedNotNull { index, path ->
                path.takeIf { it.isNotBlank() }?.let { (index + 1) to it }
            }.toMap()
        )
        Handler(Looper.getMainLooper()).post {
            callback(true, imagePaths, null)
        }
    }

    private fun processFlowDrawerJsonResponse(
        context: Context,
        screenId: String,
        jsonResponse: JSONObject,
        callback: (Boolean, List<String>?, String?) -> Unit
    ) {
        try {
            val configuredSlotCount = InfoStorage.getFlowDrawerSlotCount(context)?.coerceAtLeast(1) ?: 2
            val responseSlotCount = jsonResponse.optInt("slotCount", 0).takeIf { it > 0 }
            val slotCount = maxOf(configuredSlotCount, responseSlotCount ?: 0)
            val imageUrls = MutableList(slotCount) { "" }

            // Try to get imageUrls array first.
            when (val imageUrlsValue = jsonResponse.opt("imageUrls")) {
                is JSONArray -> {
                    for (i in 0 until minOf(imageUrlsValue.length(), slotCount)) {
                        imageUrls[i] = imageUrlsValue.optString(i, "").trim()
                    }
                }
                is JSONObject -> {
                    for (slotIndex in 1..slotCount) {
                        imageUrls[slotIndex - 1] = imageUrlsValue.optString(slotIndex.toString(), "").trim()
                    }
                }
            }

            // Fall back to explicit per-slot fields and preserve sparse slots.
            for (slotIndex in 1..slotCount) {
                val explicitUrl = jsonResponse.optString("flowDrawerImage${slotIndex}Url", "").trim()
                if (explicitUrl.isNotBlank()) {
                    imageUrls[slotIndex - 1] = explicitUrl
                }
            }

            println(
                "DEBUG: Repository.processFlowDrawerJsonResponse - Parsed " +
                    "${imageUrls.count { it.isNotBlank() }}/$slotCount image URLs"
            )
            
            // Download images to local files
            val localPaths = MutableList(slotCount) { "" }
            val flowDrawerDir = File(context.filesDir, "flow_drawer_images_$screenId")
            if (!flowDrawerDir.exists()) {
                flowDrawerDir.mkdirs()
            }
            
            // Download/verify each image
            imageUrls.forEachIndexed { index, url ->
                try {
                    val normalizedUrl = normalizeServerAssetUrl(url)
                    if (normalizedUrl != null) {
                        val extension = extractExtension(normalizedUrl).ifBlank { ".jpg" }
                        val urlHash = java.lang.Math.abs(normalizedUrl.hashCode()).toString(16)
                        val expectedName = "slot_${index + 1}_$urlHash$extension"
                        val localFile = File(flowDrawerDir, expectedName)
                        
                        // Clean up stale or mismatching files for this slot first
                        flowDrawerDir.listFiles()?.forEach { file ->
                            if (file.name.startsWith("slot_${index + 1}") && file.name != expectedName && file.name != "${expectedName}.tmp") {
                                file.delete()
                                println("DEBUG: Repository.processFlowDrawerJsonResponse - Deleted stale file: ${file.name}")
                            }
                        }
                        
                        if (localFile.exists() && localFile.length() > 0) {
                            localPaths[index] = localFile.absolutePath
                            println("DEBUG: Repository.processFlowDrawerJsonResponse - Using cached image for slot ${index + 1}: ${localFile.name}")
                        } else {
                            println("DEBUG: Repository.processFlowDrawerJsonResponse - Downloading image $index: $normalizedUrl")
                            downloadAssetWithRetry(normalizedUrl, localFile, 2)
                            if (localFile.exists() && localFile.length() > 0) {
                                localPaths[index] = localFile.absolutePath
                                println("DEBUG: Repository.processFlowDrawerJsonResponse - Successfully downloaded: ${localFile.name}")
                            } else {
                                println("DEBUG: Repository.processFlowDrawerJsonResponse - Failed to download: $normalizedUrl")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("DEBUG: Repository.processFlowDrawerJsonResponse - Error downloading image $index: ${e.message}")
                }
            }
            
            println(
                "DEBUG: Repository.processFlowDrawerJsonResponse - Downloaded " +
                    "${localPaths.count { it.isNotBlank() }}/$slotCount images"
            )
            
            // Persist snapshot for debugging
            persistFlowDrawerSnapshot(
                context = context,
                screenId = screenId,
                slotCount = slotCount,
                slotUrls = imageUrls,
                localPathsBySlot = localPaths.mapIndexedNotNull { index, path ->
                    path.takeIf { it.isNotBlank() }?.let { (index + 1) to it }
                }.toMap()
            )
            
            callback(true, localPaths, null)
        } catch (e: Exception) {
            println("DEBUG: Repository.processFlowDrawerJsonResponse - Error: ${e.message}")
            callback(false, null, "Failed to process response")
        }
    }

    private fun extractFlowDrawerSlotNumber(fileName: String): Int? {
        val match = Regex("""^slot_(\d+)""").find(fileName) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }
    
    fun updateFlowDrawerAssets(
        context: Context,
        screenId: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        notifyApiCallStarted()
        val url = "$BASE_URL/assets/$screenId"
        println("DEBUG: Repository.updateFlowDrawerAssets - URL: $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                println("DEBUG: Repository.updateFlowDrawerAssets - Network failure: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    onResult(false, "Network failure: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                println("DEBUG: Repository.updateFlowDrawerAssets - Response code: ${response.code}")
                response.use {
                    if (!response.isSuccessful) {
                        println("DEBUG: Repository.updateFlowDrawerAssets - Server error: ${response.code}")
                        Handler(Looper.getMainLooper()).post {
                            onResult(false, "Server error: ${response.code}")
                        }
                        return
                    }

                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        println("DEBUG: Repository.updateFlowDrawerAssets - Empty response body")
                        Handler(Looper.getMainLooper()).post {
                            onResult(true, "No assets found.") // Not a failure, just no assets
                        }
                        return
                    }

                    try {
                        println("DEBUG: Repository.updateFlowDrawerAssets - Parsing JSON response")
                        val newAssets = JSONArray(body)
                        println("DEBUG: Repository.updateFlowDrawerAssets - Found ${newAssets.length()} assets from server")

                        val flowDrawerDir = File(context.filesDir, "flow_drawer_assets")
                        if (!flowDrawerDir.exists()) {
                            flowDrawerDir.mkdirs()
                        }

                        val serverAssetFiles = mutableSetOf<String>()
                        for (i in 0 until newAssets.length()) {
                            val asset = newAssets.getJSONObject(i)
                            val assetUrl = asset.getString("asset_url")
                            val slotNumber = asset.getInt("slot_number")
                            val extension = assetUrl.substringAfterLast('.', "")
                            val fileName = "flow_drawer_asset_${slotNumber.toString().padStart(2, '0')}.${extension}"
                            serverAssetFiles.add(fileName)

                            val localFile = File(flowDrawerDir, fileName)
                            if (!localFile.exists()) {
                                println("DEBUG: Repository.updateFlowDrawerAssets - Downloading new asset: $fileName")
                                downloadAssetWithRetry(assetUrl, localFile, 3)
                            }
                        }

                        val localFiles = flowDrawerDir.listFiles()
                        if (localFiles != null) {
                            for (localFile in localFiles) {
                                if (localFile.name !in serverAssetFiles) {
                                    println("DEBUG: Repository.updateFlowDrawerAssets - Deleting old asset: ${localFile.name}")
                                    localFile.delete()
                                }
                            }
                        }

                        println("DEBUG: Repository.updateFlowDrawerAssets - Asset update complete.")
                        Handler(Looper.getMainLooper()).post {
                            onResult(true, null)
                        }

                    } catch (e: Exception) {
                        println("DEBUG: Repository.updateFlowDrawerAssets - Parse error: ${e.message}")
                        Handler(Looper.getMainLooper()).post {
                            onResult(false, "Parse error: ${e.message}")
                        }
                    }
                }
            }
        })
    }

    /**
     * Helper function to convert JSONObject to Map<String, Any>
     */
    private fun jsonObjectToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next() as String
            val value = json.get(key) as Any?
            val convertedValue: Any = when (value) {
                is JSONObject -> jsonObjectToMap(value)
                is org.json.JSONArray -> value.toString()
                null -> ""
                else -> value as Any
            }
            map[key] = convertedValue
        }
        return map
    }
    
    // Get health tips by BMI category
    fun getHealthTips(
        category: String,
        onResult: (Boolean, List<String>?, String?) -> Unit
    ) {
        notifyApiCallStarted()
        val encodedCategory = java.net.URLEncoder.encode(category, "UTF-8")
        val url = "$BASE_URL/health-tips/$encodedCategory"
        println("DEBUG: Repository.getHealthTips - URL: $url")
        
        val request = Request.Builder()
            .url(url)
            .addHeader("ngrok-skip-browser-warning", "true")
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                notifyApiCallFinished()
                println("DEBUG: Repository.getHealthTips - Network failure: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    onResult(false, null, "Network error: ${e.message}")
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                notifyApiCallFinished()
                response.use {
                    try {
                        val body = response.body?.string()
                        println("DEBUG: Repository.getHealthTips - Response code: ${response.code}")
                        
                        if (!response.isSuccessful || body == null) {
                            println("DEBUG: Repository.getHealthTips - Server error: ${response.code}")
                            Handler(Looper.getMainLooper()).post {
                                onResult(false, null, "Server error: ${response.code}")
                            }
                            return
                        }
                        
                        val json = JSONObject(body)
                        if (json.optBoolean("ok", false)) {
                            val tipsArray = json.optJSONArray("tips")
                            val tipsList = mutableListOf<String>()
                            if (tipsArray != null) {
                                for (i in 0 until tipsArray.length()) {
                                    tipsList.add(tipsArray.getString(i))
                                }
                            }
                            Handler(Looper.getMainLooper()).post {
                                onResult(true, tipsList, null)
                            }
                        } else {
                            val error = json.optString("error", "Unknown error")
                            Handler(Looper.getMainLooper()).post {
                                onResult(false, null, error)
                            }
                        }
                    } catch (e: Exception) {
                        println("DEBUG: Repository.getHealthTips - Parse error: ${e.message}")
                        Handler(Looper.getMainLooper()).post {
                            onResult(false, null, "Parse error: ${e.message}")
                        }
                    }
                }
            }
        })
    }
}
