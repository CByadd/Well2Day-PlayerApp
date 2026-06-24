package com.example.playerapp.utils

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Payment Token Manager
 * 
 * Manages payment tokens to ensure payment completion and guarantee transition to BMI screen.
 * 
 * Flow:
 * 1. Generate token when QR code is shown
 * 2. Include token in webUrl for web client
 * 3. Web client sends token back with payment
 * 4. Server verifies token and includes it in payment-success event
 * 5. Android verifies token before transitioning to BMI screen
 */
object PaymentTokenManager {
    // Store active tokens: token -> (bmiId, timestamp, screenId)
    private val activeTokens = ConcurrentHashMap<String, TokenData>()
    
    // Store verified tokens: token -> verification timestamp
    private val verifiedTokens = ConcurrentHashMap<String, Long>()
    
    // Token expiration time: 10 minutes
    private const val TOKEN_EXPIRATION_MS = 10 * 60 * 1000L
    
    // Token cleanup interval: 5 minutes
    private const val CLEANUP_INTERVAL_MS = 5 * 60 * 1000L
    
    data class TokenData(
        val bmiId: String,
        val screenId: String,
        val timestamp: Long,
        val token: String
    )
    
    /**
     * Generate a unique payment token for a BMI record
     * @param bmiId The BMI record ID
     * @param screenId The screen ID
     * @return The generated token
     */
    fun generateToken(bmiId: String, screenId: String): String {
        // Generate a secure random token
        val token = UUID.randomUUID().toString().replace("-", "") + 
                   Random.nextLong(1000000, 9999999).toString()
        
        val tokenData = TokenData(
            bmiId = bmiId,
            screenId = screenId,
            timestamp = System.currentTimeMillis(),
            token = token
        )
        
        activeTokens[token] = tokenData
        
        println("═══════════════════════════════════════════════════════════════")
        println("[TOKEN] 🔑 PAYMENT TOKEN GENERATED")
        println("[TOKEN] Token: $token")
        println("[TOKEN] BMI ID: $bmiId")
        println("[TOKEN] Screen ID: $screenId")
        println("[TOKEN] Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(tokenData.timestamp))}")
        println("═══════════════════════════════════════════════════════════════")
        
        // Cleanup old tokens periodically
        cleanupExpiredTokens()
        
        return token
    }
    
    /**
     * Verify a payment token
     * @param token The token to verify
     * @param bmiId The expected BMI ID
     * @param screenId The expected screen ID
     * @return true if token is valid, false otherwise
     */
    fun verifyToken(token: String, bmiId: String?, screenId: String?): Boolean {
        val tokenData = activeTokens[token]
        
        if (tokenData == null) {
            // Check if token was already verified
            if (verifiedTokens.containsKey(token)) {
                println("[TOKEN] ✅ Token already verified: $token")
                return true
            }
            
            println("[TOKEN] ❌ Token not found: $token")
            return false
        }
        
        // Check if token is expired
        val age = System.currentTimeMillis() - tokenData.timestamp
        if (age > TOKEN_EXPIRATION_MS) {
            println("[TOKEN] ❌ Token expired: $token (age: ${age}ms)")
            activeTokens.remove(token)
            return false
        }
        
        // Verify BMI ID match (if provided)
        if (bmiId != null && tokenData.bmiId != bmiId) {
            println("[TOKEN] ❌ BMI ID mismatch: expected ${tokenData.bmiId}, got $bmiId")
            return false
        }
        
        // Verify screen ID match (if provided)
        if (screenId != null && tokenData.screenId != screenId) {
            println("[TOKEN] ❌ Screen ID mismatch: expected ${tokenData.screenId}, got $screenId")
            return false
        }
        
        // Mark token as verified
        verifiedTokens[token] = System.currentTimeMillis()
        
        println("═══════════════════════════════════════════════════════════════")
        println("[TOKEN] ✅✅✅ TOKEN VERIFIED SUCCESSFULLY")
        println("[TOKEN] Token: $token")
        println("[TOKEN] BMI ID: ${tokenData.bmiId}")
        println("[TOKEN] Screen ID: ${tokenData.screenId}")
        println("[TOKEN] Token Age: ${age}ms")
        println("═══════════════════════════════════════════════════════════════")
        
        return true
    }
    
    /**
     * Get token data for a token
     * @param token The token
     * @return TokenData if found, null otherwise
     */
    fun getTokenData(token: String): TokenData? {
        return activeTokens[token]
    }
    
    /**
     * Check if a token exists and is valid (not expired)
     * @param token The token to check
     * @return true if token exists and is valid
     */
    fun isTokenValid(token: String): Boolean {
        val tokenData = activeTokens[token] ?: return verifiedTokens.containsKey(token)
        val age = System.currentTimeMillis() - tokenData.timestamp
        return age <= TOKEN_EXPIRATION_MS
    }
    
    /**
     * Remove a token (after successful payment)
     * @param token The token to remove
     */
    fun removeToken(token: String) {
        activeTokens.remove(token)
        verifiedTokens.remove(token)
        println("[TOKEN] 🗑️ Token removed: $token")
    }
    
    /**
     * Cleanup expired tokens
     */
    private fun cleanupExpiredTokens() {
        val now = System.currentTimeMillis()
        val expiredTokens = activeTokens.filter { (_, data) ->
            now - data.timestamp > TOKEN_EXPIRATION_MS
        }.keys
        
        expiredTokens.forEach { token ->
            activeTokens.remove(token)
            println("[TOKEN] 🗑️ Cleaned up expired token: $token")
        }
        
        // Also cleanup old verified tokens (older than 1 hour)
        val verifiedExpiration = 60 * 60 * 1000L // 1 hour
        val expiredVerified = verifiedTokens.filter { (_, timestamp) ->
            now - timestamp > verifiedExpiration
        }.keys
        
        expiredVerified.forEach { token ->
            verifiedTokens.remove(token)
        }
    }
    
    /**
     * Get all active tokens (for debugging)
     */
    fun getActiveTokens(): Map<String, TokenData> {
        return activeTokens.toMap()
    }
    
    /**
     * Clear all tokens (for testing/reset)
     */
    fun clearAllTokens() {
        activeTokens.clear()
        verifiedTokens.clear()
        println("[TOKEN] 🗑️ All tokens cleared")
    }
}
