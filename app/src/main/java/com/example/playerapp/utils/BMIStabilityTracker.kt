package com.example.playerapp.utils

/**
 * BMI Stability Flow Gate
 *
 * The full stability-detection algorithm (increment counter, repeated-height
 * window, findDuplicateValue) now lives in [com.example.playerapp.data.UsbSerialManager].
 * This class is therefore a thin gate that:
 *   - Prevents the BMI flow from being triggered more than once per weighing session.
 *   - Provides an idle-timeout so a forgotten flow resets itself after 30 s.
 *   - Exposes the same public API that the rest of the codebase already uses.
 */
class BMIStabilityTracker(
    @Suppress("UNUSED_PARAMETER") scope: kotlinx.coroutines.CoroutineScope,
    private val onStableReading: (weight: Float, height: Float) -> Unit
) {
    private val IDLE_TIMEOUT_MS = 30_000L   // 30 s

    @Volatile private var isFlowActive = false
    @Volatile private var lastActivityTime = System.currentTimeMillis()

    /**
     * Forward a reading from [UsbSerialManager] to [onStableReading].
     *
     * The serial manager guarantees stability before calling here, so no
     * additional debounce is needed.  We only gate duplicate triggers for
     * the same weighing session (flowFired guard).
     */
    fun addReading(weight: Float, height: Float) {
        lastActivityTime = System.currentTimeMillis()

        // weight == 0 → person stepped off; don't fire callback, just update time
        if (weight <= 0f) return

        // Already handling a flow for this weighing session
        if (isFlowActive) return

        isFlowActive = true
        onStableReading(weight, height)
    }

    /** Full reset — call when the BMI modal is dismissed or person steps off. */
    fun reset() {
        isFlowActive = false
        lastActivityTime = System.currentTimeMillis()
    }

    /** Returns true when a flow is currently active (and hasn't timed out). */
    fun isActive(): Boolean {
        if (isFlowActive && System.currentTimeMillis() - lastActivityTime > IDLE_TIMEOUT_MS) {
            reset()
            return false
        }
        return isFlowActive
    }

    /** Mark the flow as complete without resetting; allows new readings to start next session. */
    fun onFlowCompleted() {
        isFlowActive = false
    }

    /** Keep the idle-timeout clock alive while a flow is in progress. */
    fun updateActivity() {
        lastActivityTime = System.currentTimeMillis()
    }
}
