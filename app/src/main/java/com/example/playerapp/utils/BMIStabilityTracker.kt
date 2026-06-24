package com.example.playerapp.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tracks BMI sensor readings and triggers callback when readings are stable for 5 seconds.
 * Prevents multiple triggers by tracking flow state and idle timeout.
 */
class BMIStabilityTracker(
    private val scope: CoroutineScope,
    private val onStableReading: (weight: Float, height: Float) -> Unit
) {
    private var currentWeight: Float? = null
    private var currentHeight: Float? = null
    private var stabilityCheckJob: Job? = null
    private var isFlowActive = false
    private var lastActivityTime = System.currentTimeMillis()
    private val STABILITY_DURATION_MS = 1100L // 1.1 seconds
    private val IDLE_TIMEOUT_MS = 30000L // 30 seconds idle timeout
    
    /**
     * Add a new reading. If readings are stable for 1.1 seconds, triggers callback immediately.
     */
    fun addReading(weight: Float, height: Float) {
        // Update activity time
        lastActivityTime = System.currentTimeMillis()
        
        // If flow is already active, don't process new readings
        if (isFlowActive) {
            // println("DEBUG: BMIStabilityTracker - Flow is active, ignoring new reading")
            return
        }
        
        // Check if we have valid data - if so, proceed immediately after stability check
        if (weight > 0 && height > 0) {
            // Valid data acquired, check stability
            val weightTolerance = 0.1f // 0.1 kg tolerance
            val heightTolerance = 0.5f // 0.5 cm tolerance
            
            val isStable = currentWeight != null && 
                          currentHeight != null &&
                          kotlin.math.abs(weight - currentWeight!!) <= weightTolerance &&
                          kotlin.math.abs(height - currentHeight!!) <= heightTolerance
            
            if (isStable && currentWeight != null && currentHeight != null) {
                // Data is stable - if timer is already running, let it complete, otherwise trigger immediately after short delay
                if (stabilityCheckJob == null || !stabilityCheckJob!!.isActive) {
                    stabilityCheckJob = scope.launch {
                        delay(STABILITY_DURATION_MS)
                        // After 1.1 seconds of stability, trigger immediately
                        if (!isFlowActive && currentWeight != null && currentHeight != null) {
                            isFlowActive = true
                            onStableReading(currentWeight!!, currentHeight!!)
                        }
                    }
                }
            } else {
                // New reading detected - update values and restart timer
                currentWeight = weight
                currentHeight = height
                stabilityCheckJob?.cancel()
                
                // Start new stability check - when 1.1 seconds is reached, trigger immediately
                stabilityCheckJob = scope.launch {
                    delay(STABILITY_DURATION_MS)
                    // After 1.1 seconds of stability, trigger immediately
                    if (!isFlowActive && currentWeight != null && currentHeight != null) {
                        isFlowActive = true
                        onStableReading(currentWeight!!, currentHeight!!)
                    }
                }
            }
            return
        }
        
        // Original logic for backward compatibility (fallback)
        
    }
    
    /**
     * Reset the tracker (called when flow type changes or flow completes)
     */
    fun reset() {
        // println("DEBUG: BMIStabilityTracker - Resetting tracker")
        stabilityCheckJob?.cancel()
        stabilityCheckJob = null
        currentWeight = null
        currentHeight = null
        isFlowActive = false
        lastActivityTime = System.currentTimeMillis()
    }
    
    /**
     * Check if a flow is currently active
     */
    fun isActive(): Boolean {
        // Also check for idle timeout
        val timeSinceActivity = System.currentTimeMillis() - lastActivityTime
        if (isFlowActive && timeSinceActivity > IDLE_TIMEOUT_MS) {
            // println("DEBUG: BMIStabilityTracker - Idle timeout reached, resetting flow")
            reset()
            return false
        }
        return isFlowActive
    }
    
    /**
     * Mark flow as completed (called when BMI modal is dismissed)
     */
    fun onFlowCompleted() {
        // println("DEBUG: BMIStabilityTracker - Flow completed")
        isFlowActive = false
        // Don't reset readings, just mark flow as inactive
        // This allows new readings to be processed
    }
    
    /**
     * Update activity time (called periodically during active flow to prevent idle timeout)
     */
    fun updateActivity() {
        lastActivityTime = System.currentTimeMillis()
        // println("DEBUG: BMIStabilityTracker - Activity updated")
    }
}
