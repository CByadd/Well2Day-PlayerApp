package com.example.playerapp.data

import android.content.Context
import org.json.JSONObject
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Loads fortune and health tips from local JSON assets and picks items using a
 * seeded, deterministic-but-unpredictable index so each BMI reading gets a unique
 * yet reproducible choice (no server fetch).
 *
 * Seed = f(screenId, nanoTime, height, weight, category) so same reading always
 * gets the same pick; different readings get different picks.
 */
object LocalFortuneHealth {

    private const val FORTUNE_ASSET = "fortune.json"
    private const val HEALTH_ASSET = "health.json"
    private const val KEY_FORTUNE_MESSAGES = "fortune_cookie_messages"
    private const val KEY_HEALTH_TIPS = "health_tips"

    // Map server BMI category to health.json object keys
    private val CATEGORY_TO_HEALTH_KEY = mapOf(
        "Underweight" to "underweight_patients",
        "Normal" to "normal_weight_patients",
        "Overweight" to "overweight_patients",
        "Obese" to "obese_patients",
        "Morbidly Obese" to "morbidly_obese_patients"
    )

    /**
     * Picks an index in [0, size) using a deterministic hash of the seed string.
     * Uses a simple but varied mix: hash of (seed + prime) to avoid patterns.
     */
    fun pickIndex(seed: String, size: Int): Int {
        if (size <= 0) return 0
        val h = (seed.hashCode().toLong() * 31 + 17) and 0x7FFFFFFFFFL
        return (h % size).toInt().let { if (it < 0) it + size else it }
    }

    /**
     * Build a unique seed for this reading so the same reading always gets the same pick.
     */
    private fun seed(screenId: String, heightCm: Float, weightKg: Float, category: String, salt: Long): String =
        "${screenId}_${heightCm}_${weightKg}_${category}_$salt"

    private fun loadJson(context: Context, assetName: String): JSONObject? {
        return try {
            context.assets.open(assetName).use { stream ->
                InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                    JSONObject(reader.readText())
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LocalFortuneHealth", "Failed to load $assetName", e)
            null
        }
    }

    /**
     * Load list of fortune strings from assets. Returns empty list on error.
     */
    fun loadFortunes(context: Context): List<String> {
        val root = loadJson(context, FORTUNE_ASSET) ?: return emptyList()
        val arr = root.optJSONArray(KEY_FORTUNE_MESSAGES) ?: return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            arr.optString(i)?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        }
        return list
    }

    /**
     * Load health tips for a category from assets. Category should match server:
     * "Underweight", "Normal", "Overweight", "Obese", "Morbidly Obese".
     */
    fun loadHealthTipsForCategory(context: Context, category: String): List<String> {
        val root = loadJson(context, HEALTH_ASSET) ?: return emptyList()
        val tipsObj = root.optJSONObject(KEY_HEALTH_TIPS) ?: return emptyList()
        val key = CATEGORY_TO_HEALTH_KEY[category] ?: "normal_weight_patients"
        val arr = tipsObj.optJSONArray(key) ?: tipsObj.optJSONArray("normal_weight_patients") ?: return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            arr.optString(i)?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        }
        return list
    }

    /**
     * Pick one fortune and one health tip for this BMI reading. Uses nanoTime as salt
     * so the same create call gets one stable pick; different calls get different picks.
     * Returns Pair(fortuneMessage, healthTip). Either can be blank if load or pick fails.
     */
    fun pick(
        context: Context,
        screenId: String,
        heightCm: Float,
        weightKg: Float,
        category: String,
        saltNs: Long = System.nanoTime()
    ): Pair<String, String> {
        val s = seed(screenId, heightCm, weightKg, category, saltNs)
        val fortunes = loadFortunes(context)
        val fortune = if (fortunes.isEmpty()) "" else fortunes[pickIndex(s, fortunes.size)]
        val healthTips = loadHealthTipsForCategory(context, category)
        val healthTip = if (healthTips.isEmpty()) "" else healthTips[pickIndex("${s}_health", healthTips.size)]
        return Pair(fortune, healthTip)
    }
}
