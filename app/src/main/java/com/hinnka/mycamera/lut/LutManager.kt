package com.hinnka.mycamera.lut

import android.content.Context
import android.util.LruCache
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hinnka.mycamera.data.CustomImportManager
import com.hinnka.mycamera.model.ColorRecipeParams
import com.hinnka.mycamera.utils.PLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

/**
 * DataStore 扩展属性
 */
private val Context.colorRecipeDataStore: DataStore<Preferences> by preferencesDataStore(name = "color_recipe_preferences")

/**
 * LUT 管理器
 *
 * 负责 LUT 的加载、缓存和管理，以及色彩配方的持久化
 */
class LutManager(private val context: Context) {

    companion object {
        private const val TAG = "LutManager"

        // LUT 缓存大小（最多缓存 5 个 LUT）
        private const val CACHE_SIZE = 5

        // 内置 LUT 目录
        private const val BUILT_IN_LUT_FOLDER = "luts"

        // 色彩配方 DataStore Key（每个 LUT ID 存一条 JSON）
        private fun recipeKey(lutId: String, target: BaselineColorCorrectionTarget? = null) =
            stringPreferencesKey(
                target?.let { "${it.name.lowercase()}_${lutId}_recipe" } ?: "${lutId}_recipe"
            )

        // 旧版逐字段 Key（仅用于迁移读取，新数据不再写入）
        private val legacyFieldNames = listOf(
            "exposure", "contrast", "saturation", "temperature", "tint", "fade", "color",
            "highlights", "shadows", "toneToe", "toneShoulder", "tonePivot",
            "paletteX", "paletteY", "paletteDensity",
            "filmGrain", "vignette", "bleachBypass", "halation", "redHalation", "chromaticAberration",
            "noise", "lowRes",
            "skinHue", "skinChroma", "skinLightness",
            "redHue", "redChroma", "redLightness",
            "orangeHue", "orangeChroma", "orangeLightness",
            "yellowHue", "yellowChroma", "yellowLightness",
            "greenHue", "greenChroma", "greenLightness",
            "cyanHue", "cyanChroma", "cyanLightness",
            "blueHue", "blueChroma", "blueLightness",
            "purpleHue", "purpleChroma", "purpleLightness",
            "magentaHue", "magentaChroma", "magentaLightness",
            "lutIntensity"
        )

        private fun readLegacyParams(preferences: Preferences, lutId: String): ColorRecipeParams {
            fun f(name: String, default: Float = 0f) =
                preferences[floatPreferencesKey("${lutId}_$name")] ?: default
            fun s(name: String) =
                preferences[stringPreferencesKey("${lutId}_$name")] ?: ""
            return ColorRecipeParams(
                exposure = f("exposure"),
                contrast = f("contrast", 1f),
                saturation = f("saturation", 1f),
                temperature = f("temperature"),
                tint = f("tint"),
                fade = f("fade"),
                color = f("color"),
                highlights = f("highlights"),
                shadows = f("shadows"),
                toneToe = f("toneToe"),
                toneShoulder = f("toneShoulder"),
                tonePivot = f("tonePivot"),
                paletteX = f("paletteX", 0.5f),
                paletteY = f("paletteY", 0.5f),
                paletteDensity = f("paletteDensity", 1f),
                filmGrain = f("filmGrain"),
                vignette = f("vignette"),
                bleachBypass = f("bleachBypass"),
                halation = f("halation"),
                redHalation = f("redHalation"),
                chromaticAberration = f("chromaticAberration"),
                noise = f("noise"),
                lowRes = f("lowRes"),
                skinHue = f("skinHue"),
                skinChroma = f("skinChroma"),
                skinLightness = f("skinLightness"),
                redHue = f("redHue"),
                redChroma = f("redChroma"),
                redLightness = f("redLightness"),
                orangeHue = f("orangeHue"),
                orangeChroma = f("orangeChroma"),
                orangeLightness = f("orangeLightness"),
                yellowHue = f("yellowHue"),
                yellowChroma = f("yellowChroma"),
                yellowLightness = f("yellowLightness"),
                greenHue = f("greenHue"),
                greenChroma = f("greenChroma"),
                greenLightness = f("greenLightness"),
                cyanHue = f("cyanHue"),
                cyanChroma = f("cyanChroma"),
                cyanLightness = f("cyanLightness"),
                blueHue = f("blueHue"),
                blueChroma = f("blueChroma"),
                blueLightness = f("blueLightness"),
                purpleHue = f("purpleHue"),
                purpleChroma = f("purpleChroma"),
                purpleLightness = f("purpleLightness"),
                magentaHue = f("magentaHue"),
                magentaChroma = f("magentaChroma"),
                magentaLightness = f("magentaLightness"),
                lutIntensity = f("lutIntensity", 1f),
                remarks = s("remarks"),
            )
        }

        private fun androidx.datastore.preferences.core.MutablePreferences.removeLegacyKeys(lutId: String) {
            legacyFieldNames.forEach { name -> remove(floatPreferencesKey("${lutId}_$name")) }
            remove(stringPreferencesKey("${lutId}_remarks"))
        }
    }

    // LUT 缓存
    private val lutCache = LruCache<String, LutConfig>(CACHE_SIZE)

    // LUT 色彩倾向缓存 (ID -> [R, G, B])
    private val tendencyCache = mutableMapOf<String, FloatArray>()

    // 可用 LUT 列表
    private var availableLuts: List<LutInfo> = emptyList()

    // 自定义导入管理器
    private val customImportManager = CustomImportManager(context)

    /**
     * 获取指定 LUT 的色彩配方参数 Flow
     */
    fun getColorRecipeParams(
        lutId: String,
        target: BaselineColorCorrectionTarget? = null
    ): Flow<ColorRecipeParams> {
        return context.colorRecipeDataStore.data.map { preferences ->
            val json = preferences[recipeKey(lutId, target)]
            val storedParams = if (json != null) {
                ColorRecipeParams.fromJson(json)
            } else if (target == null) {
                readLegacyParams(preferences, lutId)
            } else {
                ColorRecipeParams.DEFAULT
            }

            resolveManagedRecipeParams(lutId, storedParams, target)
        }
    }

    /**
     * 初始化，扫描可用的 LUT 文件（包括内置和自定义）
     */
    fun initialize() {
        val configuredBuiltInLuts = LutParser.listAvailableLuts(context, BUILT_IN_LUT_FOLDER)
        customImportManager.initializeBuiltInLutCategoriesIfNeeded(configuredBuiltInLuts)
        val builtInLuts = configuredBuiltInLuts.map { it.copy(category = "") }
        val customLuts = customImportManager.getCustomLuts()
        val categoryOverrides = customImportManager.getCategoryOverrides()
        val favoriteOverrides = customImportManager.getFavoriteOverrides()

        // 合并列表并以 ID 去重，避免 ID 重复导致的 Jetpack Compose 主键重复崩溃
        val allLuts = (customLuts + builtInLuts).distinctBy { it.id }

        // 应用分类重写 (用户手动创建的分类会通过这里恢复)
        availableLuts = allLuts.map { lut ->
            val overriddenCategory = categoryOverrides[lut.id]
            val overriddenFavorite = favoriteOverrides[lut.id]
            lut.copy(
                category = overriddenCategory ?: lut.category,
                isFavorite = overriddenFavorite ?: lut.isFavorite
            )
        }

        PLog.d(TAG, "Found ${availableLuts.size} LUT files (${customLuts.size} custom, ${builtInLuts.size} built-in)")
    }

    /**
     * 获取可用的 LUT 列表（自定义 LUT 在前）
     */
    fun getAvailableLuts(): List<LutInfo> = availableLuts

    /**
     * 通过 ID 获取 LUT 信息
     */
    fun getLutInfo(id: String): LutInfo? {
        return availableLuts.find { it.id == id }
    }

    /**
     * 加载 LUT 配置
     *
     * @param id LUT ID
     * @return LUT 配置，如果加载失败返回 null
     */
    fun loadLut(id: String): LutConfig? {
        // 先从缓存查找
        lutCache.get(id)?.let {
            //PLog.d(TAG, "LUT loaded from cache: $id")
            return it
        }

        // 查找 LUT 信息
        val lutInfo = getLutInfo(id) ?: run {
//            PLog.w(TAG, "LUT not found: $id")
            return null
        }

        // 从文件加载
        return try {
            val lutConfig = if (lutInfo.isBuiltIn) {
                if (lutInfo.fileName.isBlank()) {
                    return null
                }
                // 内置 LUT 从 assets 加载
                LutParser.parseFromAssets(context, lutInfo.fileName)
            } else {
                // 自定义 LUT 从文件系统加载
                java.io.File(lutInfo.fileName).inputStream().use { inputStream ->
                    LutParser.parse(inputStream, lutInfo.getName())
                }
            }

            if (lutConfig.isValid()) {
                // 添加到缓存
                lutCache.put(id, lutConfig)
//                PLog.d(TAG, "LUT loaded: $id, size: ${lutConfig.size}")
                lutConfig
            } else {
                PLog.e(TAG, "Invalid LUT data: $id")
                null
            }
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to load LUT: $id", e)
            null
        }
    }

    /**
     * 清除缓存中的特定 LUT
     */
    fun evictLut(id: String) {
        lutCache.remove(id)
    }

    /**
     * 清除所有缓存
     */
    fun clearCache() {
        lutCache.evictAll()
        PLog.d(TAG, "LUT cache cleared")
    }

    /**
     * 获取缓存状态信息
     */
    fun getCacheInfo(): String {
        return "LUT Cache: ${lutCache.size()}/${CACHE_SIZE}, hits=${lutCache.hitCount()}, misses=${lutCache.missCount()}"
    }

    /**
     * 获取指定 LUT 的色彩倾向性（带缓存）
     */
    fun getLutTendency(id: String): FloatArray? {
        tendencyCache[id]?.let { return it }
        
        val lutConfig = loadLut(id) ?: return null
        val tendency = LutColorAnalyzer.analyzeTendency(lutConfig)
        tendencyCache[id] = tendency
        return tendency
    }

    /**
     * 为指定颜色推荐最合适的 LUT 列表
     * @param targetColor 目标颜色 (Color Int)
     * @param limit 推荐数量
     * @return 按匹配度排序的 LUT 列表
     */
    fun recommendLutsForColor(targetColor: Int, limit: Int = 5): List<LutInfo> {
        return availableLuts
            .mapNotNull { info ->
                val tendency = getLutTendency(info.id) ?: return@mapNotNull null
                val score = LutColorAnalyzer.calculateSuitability(targetColor, tendency)
                info to score
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    // ========== 色彩配方持久化方法 ==========

    /**
     * 保存指定 LUT 的色彩配方参数
     *
     * @param lutId LUT ID
     * @param params 色彩配方参数
     */
    suspend fun saveColorRecipeParams(
        lutId: String,
        params: ColorRecipeParams,
        target: BaselineColorCorrectionTarget? = null
    ) {
        context.colorRecipeDataStore.edit { preferences ->
            preferences[recipeKey(lutId, target)] = params.toJson()
            if (target == null) {
                preferences.removeLegacyKeys(lutId)
            }
        }
//        PLog.d(TAG, "Color recipe params saved for LUT [$lutId]: $params")
    }

    /**
     * 加载指定 LUT 的色彩配方参数（一次性读取）
     *
     * @param lutId LUT ID
     * @return 色彩配方参数，如果未设置则返回默认值
     */
    suspend fun loadColorRecipeParams(
        lutId: String,
        target: BaselineColorCorrectionTarget? = null
    ): ColorRecipeParams {
        return context.colorRecipeDataStore.data.map { preferences ->
            val json = preferences[recipeKey(lutId, target)]
            val storedParams = if (json != null) {
                ColorRecipeParams.fromJson(json)
            } else if (target == null) {
                readLegacyParams(preferences, lutId)
            } else {
                ColorRecipeParams.DEFAULT
            }

            resolveManagedRecipeParams(lutId, storedParams, target)
        }.firstOrNull() ?: ColorRecipeParams.DEFAULT
    }

    /**
     * 重置指定 LUT 的色彩配方参数为默认值
     *
     * @param lutId LUT ID
     */
    suspend fun resetColorRecipeParams(
        lutId: String,
        target: BaselineColorCorrectionTarget? = null
    ) {
        saveColorRecipeParams(lutId, ColorRecipeParams.DEFAULT, target)
        PLog.d(TAG, "Color recipe params reset to default for LUT [$lutId]")
    }

    /**
     * 删除指定 LUT 的色彩配方参数
     *
     * @param lutId LUT ID
     */
    suspend fun deleteColorRecipeParams(
        lutId: String,
        target: BaselineColorCorrectionTarget? = null
    ) {
        context.colorRecipeDataStore.edit { preferences ->
            preferences.remove(recipeKey(lutId, target))
            if (target == null) {
                preferences.removeLegacyKeys(lutId)
            }
        }
        PLog.d(TAG, "Color recipe params deleted for LUT [$lutId]")
    }

    /**
     * Managed camera-style defaults for built-in / known LUTs.
     *
     * Important: None and baseline targets must stay neutral. The camera feed is already ISP-tonemapped,
     * so baseline processing should not silently pre-grade the frame before a LUT is applied.
     *
     * For creative LUTs, these values deliberately reduce LUT strength and open the lower mids so the
     * new cleaner/brighter base does not get crushed into a dark “filter on phone image” look.
     */
    private fun resolveManagedRecipeParams(
        lutId: String,
        storedParams: ColorRecipeParams,
        target: BaselineColorCorrectionTarget?
    ): ColorRecipeParams {
        val id = lutId.lowercase()

        // Never let target/baseline layers add hidden color processing.
        if (target != null) return ColorRecipeParams.DEFAULT

        // None must remain a true passthrough recipe.
        if (id == "none" || id.contains("none")) return ColorRecipeParams.DEFAULT

        return when {
            id.contains("m9") || id.contains("ccd") -> storedParams.copy(
                // CCD intent, but less density: avoid blocked black fur / collapsed faces.
                exposure = 0.06f,
                contrast = 0.90f,
                saturation = 0.86f,
                temperature = -0.015f,
                tint = 0.005f,
                color = -0.045f,
                highlights = -0.10f,
                shadows = 0.24f,
                toneToe = 0.16f,
                toneShoulder = -0.06f,
                tonePivot = 0.02f,
                bleachBypass = 0f,
                halation = 0f,
                redHalation = 0f,
                chromaticAberration = 0f,
                noise = 0f,
                lowRes = 0f,
                orangeChroma = -0.045f,
                yellowChroma = -0.030f,
                blueChroma = -0.020f,
                primaryRedSaturation = -0.025f,
                primaryBlueSaturation = -0.020f,
                lutIntensity = 0.72f,
                remarks = "Managed M9 CCD v10: softer density, lifted lower mids, reduced warm crush"
            )

            id.contains("m240") || id.contains("240") -> storedParams.copy(
                // M240 should be clearly smoother / more digital-film than M9.
                exposure = 0.04f,
                contrast = 0.94f,
                saturation = 0.82f,
                temperature = -0.020f,
                tint = 0.000f,
                color = -0.035f,
                highlights = -0.08f,
                shadows = 0.20f,
                toneToe = 0.12f,
                toneShoulder = -0.04f,
                tonePivot = 0.01f,
                bleachBypass = 0f,
                halation = 0f,
                redHalation = 0f,
                chromaticAberration = 0f,
                noise = 0f,
                lowRes = 0f,
                orangeChroma = -0.035f,
                yellowChroma = -0.020f,
                blueChroma = -0.015f,
                lutIntensity = 0.66f,
                remarks = "Managed M240 v10: smooth film, lower saturation, less bite than M9"
            )

            id.contains("leica") || id.contains("natural") || id.contains("nat") -> storedParams.copy(
                // Natural should sit between None and M9: clean, soft, not dense.
                exposure = 0.03f,
                contrast = 0.96f,
                saturation = 0.88f,
                temperature = -0.010f,
                color = -0.025f,
                highlights = -0.06f,
                shadows = 0.14f,
                toneToe = 0.08f,
                toneShoulder = -0.03f,
                tonePivot = 0.00f,
                bleachBypass = 0f,
                halation = 0f,
                redHalation = 0f,
                chromaticAberration = 0f,
                noise = 0f,
                lowRes = 0f,
                orangeChroma = -0.020f,
                yellowChroma = -0.015f,
                lutIntensity = 0.70f,
                remarks = "Managed Leica Natural v10: clean, restrained, softer than previous recipes"
            )

            id.contains("kodak") || id.contains("dcs") -> storedParams.copy(
                // Kodak can keep color separation, but not the thick warm shadow density.
                exposure = 0.04f,
                contrast = 0.93f,
                saturation = 0.87f,
                temperature = -0.010f,
                tint = 0.005f,
                color = -0.030f,
                highlights = -0.07f,
                shadows = 0.18f,
                toneToe = 0.10f,
                toneShoulder = -0.04f,
                tonePivot = 0.01f,
                bleachBypass = 0f,
                halation = 0f,
                redHalation = 0f,
                chromaticAberration = 0f,
                noise = 0f,
                lowRes = 0f,
                orangeChroma = -0.025f,
                yellowChroma = -0.020f,
                blueChroma = -0.010f,
                lutIntensity = 0.70f,
                remarks = "Managed Kodak v10: color separation without heavy density"
            )

            id.contains("hasselblad") || id.contains("hncs") -> storedParams.copy(
                // HNCS-style: smoother color, gentle contrast, less magenta/orange heaviness.
                exposure = 0.04f,
                contrast = 0.92f,
                saturation = 0.84f,
                temperature = -0.012f,
                tint = -0.006f,
                color = -0.040f,
                highlights = -0.07f,
                shadows = 0.19f,
                toneToe = 0.10f,
                toneShoulder = -0.04f,
                tonePivot = 0.01f,
                bleachBypass = 0f,
                halation = 0f,
                redHalation = 0f,
                chromaticAberration = 0f,
                noise = 0f,
                lowRes = 0f,
                orangeChroma = -0.035f,
                yellowChroma = -0.020f,
                blueChroma = -0.015f,
                lutIntensity = 0.68f,
                remarks = "Managed Hasselblad v10: smoother HNCS-style colour, lighter shadows"
            )

            id.contains("pentax") || id.contains("satobi") -> storedParams.copy(
                // Satobi is muted and nostalgic; keep that, but stop it from becoming grey/dense.
                exposure = 0.05f,
                contrast = 0.90f,
                saturation = 0.76f,
                temperature = -0.005f,
                tint = -0.004f,
                fade = 0.035f,
                color = -0.050f,
                highlights = -0.06f,
                shadows = 0.22f,
                toneToe = 0.14f,
                toneShoulder = -0.04f,
                tonePivot = 0.01f,
                bleachBypass = 0f,
                halation = 0f,
                redHalation = 0f,
                chromaticAberration = 0f,
                noise = 0f,
                lowRes = 0f,
                orangeChroma = -0.045f,
                yellowChroma = -0.035f,
                greenChroma = -0.020f,
                blueChroma = -0.020f,
                lutIntensity = 0.66f,
                remarks = "Managed Pentax Satobi v10: muted but less grey/crushed"
            )

            else -> storedParams
        }
    }

}
