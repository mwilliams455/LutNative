package com.hinnka.mycamera.preview

import android.content.Context
import android.graphics.Bitmap
import com.hinnka.mycamera.data.AiFocusTargetMode
import com.hinnka.mycamera.ml.SharedYoloXObjectDetector
import com.hinnka.mycamera.ml.YoloXObjectDetector
import com.hinnka.mycamera.utils.PLog
import com.hinnka.mycamera.utils.StartupTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot

class PreviewAiFocusProcessor(private val context: Context) {
    companion object {
        private const val TAG = "PreviewAiFocusProcessor"
        private const val MIN_FOCUS_INTERVAL_MS = 60L
        private const val MIN_TARGET_MOVE = 0.025f
        private const val TRACK_KEEP_DISTANCE = 0.22f
        private const val TARGET_LOST_CONFIRM_FRAMES = 5
    }

    data class FocusTarget(
        val x: Float,
        val y: Float,
        val label: String,
        val score: Float,
        val priority: Int,
    )

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    @Volatile
    private var isPrewarming = false
    @Volatile
    private var isPrewarmed = false
    @Volatile
    private var isProcessing = false
    @Volatile
    var targetMode: AiFocusTargetMode = AiFocusTargetMode.PERSON
    @Volatile
    var scoreThreshold: Float = 0.5f
    private var lastFocusTimeMs = 0L
    private var lastFocusX = -1f
    private var lastFocusY = -1f
    private var targetMissingFrames = 0
    private var lastDetectionLogTimeMs = 0L
    var onFocusTarget: ((FocusTarget) -> Unit)? = null
    var onTargetSeen: ((FocusTarget) -> Unit)? = null
    var onTargetLost: (() -> Unit)? = null

    fun prewarm() {
        if (isPrewarmed || isPrewarming) return
        isPrewarming = true
        scope.launch {
            try {
                StartupTrace.mark("PreviewAiFocusProcessor.prewarm start")
                SharedYoloXObjectDetector.prewarm(context)
                isPrewarmed = true
                StartupTrace.mark("PreviewAiFocusProcessor.prewarm end")
            } catch (e: Exception) {
                PLog.e(TAG, "Failed to prewarm YOLOX object detector", e)
            } finally {
                isPrewarming = false
            }
        }
    }

    fun processBitmap(bitmap: Bitmap) {
        if (targetMode == AiFocusTargetMode.OFF) {
            handleMissingTarget()
            return
        }
        if (isProcessing) return
        if (!isPrewarmed) {
            prewarm()
            return
        }

        isProcessing = true
        scope.launch {
            val startTimeMs = System.currentTimeMillis()
            try {
                val detections = SharedYoloXObjectDetector.detect(
                    context = context,
                    bitmap = bitmap,
                    targetMode = targetMode,
                    scoreThreshold = scoreThreshold
                )
                val target = selectFocusTarget(detections)
                if (target == null) {
                    handleMissingTarget()
                    return@launch
                }
                targetMissingFrames = 0
                val seenTarget = FocusTarget(
                    x = target.focusX,
                    y = target.focusY,
                    label = target.label,
                    score = target.score,
                    priority = target.priority,
                )
                onTargetSeen?.invoke(seenTarget)
                logTargetSeen(target, startTimeMs, System.currentTimeMillis() - startTimeMs)
                if (!shouldRefocus(target)) return@launch

                lastFocusTimeMs = System.currentTimeMillis()
                lastFocusX = target.focusX
                lastFocusY = target.focusY
                PLog.d(
                    TAG,
                    "AI focus target: label=${target.label} score=${target.score} x=${target.focusX} y=${target.focusY}"
                )
                onFocusTarget?.invoke(seenTarget)
            } catch (e: Exception) {
                PLog.e(TAG, "Error processing preview bitmap for AI focus", e)
            } finally {
                isProcessing = false
            }
        }
    }

    fun release() {
        isPrewarmed = false
    }

    private fun selectFocusTarget(
        detections: List<YoloXObjectDetector.Detection>,
    ): YoloXObjectDetector.Detection? {
        if (detections.isEmpty()) return null

        val hasTrackedTarget = lastFocusX >= 0f && lastFocusY >= 0f
        if (hasTrackedTarget) {
            val trackedPriority = detections.maxOf { it.priority }
            val trackedCandidates = detections
                .filter { it.priority == trackedPriority }
                .map { detection ->
                    val distance = hypot(detection.focusX - lastFocusX, detection.focusY - lastFocusY)
                    detection to distance
                }
                .filter { (_, distance) -> distance <= TRACK_KEEP_DISTANCE }

            if (trackedCandidates.isNotEmpty()) {
                return trackedCandidates.maxWithOrNull(
                    compareBy<Pair<YoloXObjectDetector.Detection, Float>> { -it.second }
                        .thenBy { it.first.score }
                        .thenBy { it.first.area }
                )?.first
            }
        }

        return detections.maxWithOrNull(
            compareBy<YoloXObjectDetector.Detection> { it.priority }
                .thenBy { it.score }
                .thenBy { it.area }
        )
    }

    private fun shouldRefocus(target: YoloXObjectDetector.Detection): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastFocusTimeMs < MIN_FOCUS_INTERVAL_MS) return false
        if (lastFocusX < 0f || lastFocusY < 0f) return true
        return hypot(target.focusX - lastFocusX, target.focusY - lastFocusY) >= MIN_TARGET_MOVE
    }

    private fun handleMissingTarget() {
        if (lastFocusX < 0f || lastFocusY < 0f) return
        targetMissingFrames++
        if (targetMissingFrames < TARGET_LOST_CONFIRM_FRAMES) return

        PLog.d(TAG, "AI focus target lost: frames=$targetMissingFrames")
        targetMissingFrames = 0
        lastFocusX = -1f
        lastFocusY = -1f
        lastFocusTimeMs = 0L
        onTargetLost?.invoke()
    }

    private fun logTargetSeen(target: YoloXObjectDetector.Detection, startTimeMs: Long, elapsedMs: Long) {
        val intervalMs = if (lastDetectionLogTimeMs > 0L) startTimeMs - lastDetectionLogTimeMs else 0L
        lastDetectionLogTimeMs = startTimeMs
        PLog.d(
            TAG,
            "AI target seen: label=${target.label} score=${target.score} x=${target.focusX} y=${target.focusY} interval=${intervalMs}ms inference=${elapsedMs}ms"
        )
    }
}
