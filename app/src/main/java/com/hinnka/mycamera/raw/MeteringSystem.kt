package com.hinnka.mycamera.raw

import com.hinnka.mycamera.color.TransferCurve
import com.hinnka.mycamera.utils.PLog
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * 评价测光与场景分析系统
 */
object MeteringSystem {
    private const val TAG = "MeteringSystem"
    private const val DISPLAY_TARGET_LUMA = 0.52f
    private const val DISPLAY_HIGHLIGHT_LIMIT = 0.98f

    fun analyze(
        floatBuffer: FloatBuffer,
        width: Int,
        height: Int,
        metadata: RawMetadata?,
        centerWeight: Float = 0f
    ): Float {
        val pixelCount = width * height
        val allLumas = FloatArray(pixelCount)
        var validPixelCount = 0

        val gridRows = 12
        val gridCols = 12
        val zoneSum = DoubleArray(gridRows * gridCols)
        val zonePixCount = IntArray(gridRows * gridCols)
        val zoneMaxL = FloatArray(gridRows * gridCols)
        val zoneSkinCount = IntArray(gridRows * gridCols)

        var maxLuma = 0.0f

        floatBuffer.position(0)
        for (y in 0 until height) {
            val gy = (y.toFloat() / height * gridRows).toInt().coerceIn(0, gridRows - 1)
            for (x in 0 until width) {
                val gx = (x.toFloat() / width * gridCols).toInt().coerceIn(0, gridCols - 1)
                val zi = gy * gridCols + gx

                val r = floatBuffer.get()
                val g = floatBuffer.get()
                val b = floatBuffer.get()
                floatBuffer.get() // skip alpha

                val luma = r * 0.2126f + g * 0.7152f + b * 0.0722f
                if (luma.isNaN() || luma < 0f) continue

                if (luma > maxLuma) maxLuma = luma
                allLumas[validPixelCount++] = luma

                zoneSum[zi] += luma.toDouble()
                zonePixCount[zi]++
                if (luma > zoneMaxL[zi]) zoneMaxL[zi] = luma

                // 2. 肤色检测逻辑
                if (r > g && g > b && g > 0.001f) {
                    val rgRatio = r / g
                    val gbRatio = g / b
                    if (rgRatio in 1.1f..2.5f && gbRatio in 1.0f..3.0f) {
                        zoneSkinCount[zi]++
                    }
                }
            }
        }

        if (validPixelCount == 0) {
            return 1f
        }

        // ---------------------------------------------------------
        // 区域评价测光算法 (Evaluative Metering Algorithm)
        // ---------------------------------------------------------
        var totalWeight = 0.0
        var weightedSumLog = 0.0

        // 计算全局平均亮度，用于逆光检测对比
        var globalTotalLog = 0.0
        var globalTotalPixels = 0
        for (i in 0 until gridRows * gridCols) {
            globalTotalLog += zoneSum[i]
            globalTotalPixels += zonePixCount[i]
        }
        val globalAvgLog = if (globalTotalPixels > 0) globalTotalLog / globalTotalPixels else 0.0

        for (i in 0 until gridRows * gridCols) {
            if (zonePixCount[i] == 0) continue

            val gy = i / gridCols
            val gx = i % gridCols
            val pzx = (gx + 0.5f) / gridCols
            val pzy = (gy + 0.5f) / gridRows

            val zoneAvg = zoneSum[i] / zonePixCount[i]

            // 1. 基础权重：从评价测光连续过渡到中央重点测光。
            val distSqC = (pzx - 0.5f).let { it * it } + (pzy - 0.5f).let { it * it }

            val normalizedCenterWeight = centerWeight.coerceIn(0f, 1f).toDouble()
            val centerInfluence = normalizedCenterWeight * normalizedCenterWeight
            var weight = calculateSpatialMeteringWeight(distSqC.toDouble(), centerInfluence)

            // 2. 肤色密度补偿
            val skinDensity = zoneSkinCount[i].toDouble() / zonePixCount[i]
            if (skinDensity > 0.05) {
                weight *= (1.0 + skinDensity * 12.0)
            }

            // 3. 高光评价逻辑 (Sky/Highlight handling)
            weight *= calculateHighlightSuppression(zoneAvg, centerInfluence)

            // 4. 逆光补偿 (Backlight compensation)
            // 如果对焦中心区域明显暗于全局平均，说明可能处于大面积强光背后的阴影中，大幅增加权重以拉亮主体
            weight *= calculateCenterBacklightBoost(zoneAvg, globalAvgLog, distSqC.toDouble(), centerInfluence)

            // 5. 中央重点暗部优先
            // 高中心权重时，暗的中心区域应主导测光；否则大面积天空会把平均值抬高，肉眼上仍然欠曝。
            if (centerInfluence > 0.0 && distSqC < 0.12 && zoneAvg < TransferCurve.LINEAR.middleGray) {
                val darkness = 1.0 - (zoneAvg / TransferCurve.LINEAR.middleGray).coerceIn(0.0, 1.0)
                weight *= 1.0 + darkness * 8.0 * centerInfluence
            }

            weightedSumLog += zoneAvg * weight
            totalWeight += weight
        }

        // 计算加权平均亮度
        if (totalWeight <= 0.0) {
            return 1f
        }

        val avg = (weightedSumLog / totalWeight).toFloat()
        if (avg <= 0.000001f) {
            return 1f
        }

        val exposureBias = metadata?.exposureBias?.let { if (it > -10 && it < 10) it else 0f } ?: 0f
        val biasMultiplier = 2.0f.pow(exposureBias)

        val targetLumaIRE = TransferCurve.LINEAR.middleGray
        val gain = targetLumaIRE * biasMultiplier / avg
        return gain.coerceIn(0.25f, 16f)
    }

    fun analyzeRenderedExposureEv(
        byteBuffer: ByteBuffer,
        width: Int,
        height: Int,
        centerWeight: Float = 0f
    ): Float {
        val gridRows = 12
        val gridCols = 12
        val zoneSum = DoubleArray(gridRows * gridCols)
        val zonePixCount = IntArray(gridRows * gridCols)
        val lumas = FloatArray(width * height)
        var validPixelCount = 0

        byteBuffer.position(0)
        for (y in 0 until height) {
            val gy = (y.toFloat() / height * gridRows).toInt().coerceIn(0, gridRows - 1)
            for (x in 0 until width) {
                val gx = (x.toFloat() / width * gridCols).toInt().coerceIn(0, gridCols - 1)
                val zi = gy * gridCols + gx

                val r = (byteBuffer.get().toInt() and 0xFF) / 255f
                val g = (byteBuffer.get().toInt() and 0xFF) / 255f
                val b = (byteBuffer.get().toInt() and 0xFF) / 255f
                byteBuffer.get()

                val luma = r * 0.2126f + g * 0.7152f + b * 0.0722f
                lumas[validPixelCount++] = luma
                zoneSum[zi] += luma.toDouble()
                zonePixCount[zi]++
            }
        }

        if (validPixelCount == 0) {
            return 0f
        }

        var totalWeight = 0.0
        var weightedSum = 0.0
        val normalizedCenterWeight = centerWeight.coerceIn(0f, 1f).toDouble()
        val centerInfluence = normalizedCenterWeight * normalizedCenterWeight

        for (i in 0 until gridRows * gridCols) {
            if (zonePixCount[i] == 0) continue

            val gy = i / gridCols
            val gx = i % gridCols
            val pzx = (gx + 0.5f) / gridCols
            val pzy = (gy + 0.5f) / gridRows
            val distSqC = (pzx - 0.5f).let { it * it } + (pzy - 0.5f).let { it * it }
            val weight = calculateSpatialMeteringWeight(distSqC.toDouble(), centerInfluence)
            val zoneAvg = zoneSum[i] / zonePixCount[i]

            weightedSum += zoneAvg * weight
            totalWeight += weight
        }

        if (totalWeight <= 0.0) {
            return 0f
        }

        val averageLuma = (weightedSum / totalWeight).toFloat()
        if (averageLuma <= 0.000001f) {
            return 0f
        }

//        val histogram = lumas.copyOf(validPixelCount)
//        histogram.sort()
//        val highlightPercentile = percentile(histogram, 0.995f)
        val meteredEv = log2(DISPLAY_TARGET_LUMA / averageLuma)
//        val highlightLimitedEv = if (meteredEv > 0f && highlightPercentile > 0f) {
//            minOf(meteredEv, log2(DISPLAY_HIGHLIGHT_LIMIT / highlightPercentile).coerceAtLeast(0f))
//        } else {
//            meteredEv
//        }
//        PLog.d(
//            TAG,
//            "Rendered metering: avg=$averageLuma p99.5=$highlightPercentile ev=$meteredEv limited=$highlightLimitedEv"
//        )
//        return highlightLimitedEv.coerceIn(-2f, 2f)
        return meteredEv.coerceIn(-2f, 4f)
    }

    private fun percentile(sortedValues: FloatArray, percentile: Float): Float {
        if (sortedValues.isEmpty()) {
            return 0f
        }

        val index = ((sortedValues.size - 1) * percentile.coerceIn(0f, 1f)).toInt()
        return sortedValues[index]
    }

    private fun log2(value: Float): Float {
        return (ln(value.toDouble()) / ln(2.0)).toFloat()
    }

    private fun calculateSpatialMeteringWeight(distSqC: Double, centerInfluence: Double): Double {
        if (centerInfluence <= 0.0) {
            return 1.0
        }

        val centerSigma = 0.24 - 0.17 * centerInfluence
        val centerBoost = 1.0 + 3.0 * centerInfluence
        val centerWeightedWeight = exp(-distSqC / centerSigma) * centerBoost
        return 1.0 * (1.0 - centerInfluence) + centerWeightedWeight * centerInfluence
    }

    private fun calculateHighlightSuppression(zoneAvg: Double, centerInfluence: Double): Double {
        val highlightThreshold = 0.75 - 0.25 * centerInfluence
        if (zoneAvg <= highlightThreshold) {
            return 1.0
        }

        return 0.25 / (1.0 + centerInfluence)
    }

    private fun calculateCenterBacklightBoost(
        zoneAvg: Double,
        globalAvg: Double,
        distSqC: Double,
        centerInfluence: Double
    ): Double {
        if (centerInfluence <= 0.0 || zoneAvg >= globalAvg - 0.05) {
            return 1.0
        }

        val centerProximity = exp(-distSqC / 0.08)
        return 1.0 + centerProximity * (3.0 + 6.0 * centerInfluence) * centerInfluence
    }

}
