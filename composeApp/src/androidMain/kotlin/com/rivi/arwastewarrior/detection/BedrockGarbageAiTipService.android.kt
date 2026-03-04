package com.rivi.arwastewarrior.detection

import android.util.Log
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.auth.result.AuthSessionResult
import com.amplifyframework.core.Amplify
import com.rivi.arwastewarrior.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

actual fun platformGarbageAiTipService(): GarbageAiTipService {
    return BedrockGarbageAiTipService(
        fallback = RuleBasedGarbageAiTipService()
    )
}

private class BedrockGarbageAiTipService(
    private val fallback: GarbageAiTipService
) : GarbageAiTipService {
    private val tag = "BedrockGarbageTip"
    private var bedrockEnabled = true
    private val endpointUrl: String = BuildConfig.BEDROCK_AI_ENDPOINT.trim()
    private val modelId: String = BuildConfig.BEDROCK_MODEL_ID
        .trim()
        .ifBlank { "global.amazon.nova-2-lite-v1:0" }

    override suspend fun analyzeEncounter(
        category: GarbageCategory,
        confidence: Int,
        recommendedBinNearby: Boolean,
        language: AppLanguage,
        garbageSize: GarbageSize,
        binClosed: Boolean?,
        binOverflowing: Boolean?,
        likelyGarbage: Boolean,
        rawLabels: List<String>,
        detectedBins: List<BinObservation>
    ): GarbageAiEncounter {
        return withContext(Dispatchers.IO) {
            if (!bedrockEnabled || endpointUrl.isBlank()) {
                if (endpointUrl.isBlank()) {
                    bedrockEnabled = false
                }
                return@withContext fallback.analyzeEncounter(
                    category = category,
                    confidence = confidence,
                    recommendedBinNearby = recommendedBinNearby,
                    language = language,
                    garbageSize = garbageSize,
                    binClosed = binClosed,
                    binOverflowing = binOverflowing,
                    likelyGarbage = likelyGarbage,
                    rawLabels = rawLabels,
                    detectedBins = detectedBins
                )
            }
            try {
                val payload = JSONObject()
                    .put("category", category.label)
                    .put("recommendedBin", category.recommendedBin)
                    .put("confidence", confidence)
                    .put("recommendedBinNearby", recommendedBinNearby)
                    .put("language", language.code)
                    .put("modelId", modelId)
                    .put("gameMode", "REAL")
                    .put("likelyGarbage", likelyGarbage)
                    .put("garbageSize", garbageSize.name)
                    .put("binClosed", binClosed)
                    .put("binOverflowing", binOverflowing)
                    .put("rawLabels", JSONArray(rawLabels.take(12)))
                    .put("detectedBins", toJsonBins(detectedBins))
                    .toString()

                val jwtToken = fetchJwtToken()
                val responseBody = postJson(
                    url = endpointUrl,
                    payload = payload,
                    bearerToken = jwtToken
                )
                parseBackendEncounter(
                    rawJson = responseBody,
                    inputCategory = category,
                    language = language,
                    likelyGarbage = likelyGarbage,
                    recommendedBinNearby = recommendedBinNearby,
                    garbageSize = garbageSize,
                    binClosed = binClosed,
                    binOverflowing = binOverflowing
                ) ?: fallback.analyzeEncounter(
                    category = category,
                    confidence = confidence,
                    recommendedBinNearby = recommendedBinNearby,
                    language = language,
                    garbageSize = garbageSize,
                    binClosed = binClosed,
                    binOverflowing = binOverflowing,
                    likelyGarbage = likelyGarbage,
                    rawLabels = rawLabels,
                    detectedBins = detectedBins
                )
            } catch (e: Exception) {
                Log.w(tag, "Bedrock call failed, using fallback tip", e)
                fallback.analyzeEncounter(
                    category = category,
                    confidence = confidence,
                    recommendedBinNearby = recommendedBinNearby,
                    language = language,
                    garbageSize = garbageSize,
                    binClosed = binClosed,
                    binOverflowing = binOverflowing,
                    likelyGarbage = likelyGarbage,
                    rawLabels = rawLabels,
                    detectedBins = detectedBins
                )
            }
        }
    }

    private fun parseBackendEncounter(
        rawJson: String,
        inputCategory: GarbageCategory,
        language: AppLanguage,
        likelyGarbage: Boolean,
        recommendedBinNearby: Boolean,
        garbageSize: GarbageSize,
        binClosed: Boolean?,
        binOverflowing: Boolean?
    ): GarbageAiEncounter? {
        return try {
            val root = JSONObject(rawJson)
            val obj = if (root.has("body") && root.opt("body") is String) {
                JSONObject(root.optString("body"))
            } else {
                root
            }
            val tip = obj.optString("tip")
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return null

            val resolvedCategory = parseGarbageCategory(obj.optString("resolvedCategory"))
                ?: inputCategory.takeIf { it != GarbageCategory.UNKNOWN }
            val isGarbage = obj.optBoolean(
                "isGarbage",
                likelyGarbage && resolvedCategory != null
            ) && resolvedCategory != null

            val demonType = parseDemonType(obj.optString("demonType"), resolvedCategory)
            val demonCount = obj.optInt("demonCount", defaultDemonCount(garbageSize, binClosed, binOverflowing))
                .coerceIn(0, 8)
            val dominantKind = defaultDominantKind(resolvedCategory)
            val demonMix = parseDemonMix(
                rawArray = obj.optJSONArray("demonMix"),
                totalCount = if (isGarbage) demonCount.coerceAtLeast(1) else 0,
                dominantKind = dominantKind
            )
            val diseaseText = firstNonBlank(
                if (language == AppLanguage.HINDI) {
                    listOf(
                        obj.optString("diseaseWarningHindi"),
                        obj.optString("diseaseWarning"),
                        obj.optString("diseaseWarningText"),
                        obj.optString("diseaseWarningEnglish")
                    )
                } else {
                    listOf(
                        obj.optString("diseaseWarningEnglish"),
                        obj.optString("diseaseWarning"),
                        obj.optString("diseaseWarningText"),
                        obj.optString("diseaseWarningHindi")
                    )
                }
            ) ?: defaultDiseaseWarning(language)
            val speechText = firstNonBlank(
                if (language == AppLanguage.HINDI) {
                    listOf(
                        obj.optString("speechTextHindi"),
                        obj.optString("speechText"),
                        obj.optString("speechTextEnglish")
                    )
                } else {
                    listOf(
                        obj.optString("speechTextEnglish"),
                        obj.optString("speechText"),
                        obj.optString("speechTextHindi")
                    )
                }
            ) ?: diseaseText
            val binIssue = obj.optString("binIssue")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: fallbackBinIssue(
                    recommendedBinNearby = recommendedBinNearby,
                    binClosed = binClosed,
                    binOverflowing = binOverflowing,
                    isGarbage = isGarbage,
                    language = language
                )
            val actionPrompt = obj.optString("actionPrompt")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: fallbackActionPrompt(
                    recommendedBinNearby = recommendedBinNearby,
                    isGarbage = isGarbage,
                    language = language
                )

            GarbageAiEncounter(
                isGarbage = isGarbage,
                resolvedCategory = resolvedCategory,
                tip = tip,
                binIssue = binIssue,
                actionPrompt = actionPrompt,
                demonType = demonType,
                demonCount = if (isGarbage) demonCount.coerceAtLeast(1) else 0,
                demonMix = demonMix,
                gameModeOptions = listOf(GameModeOption.REAL),
                recommendedMode = GameModeOption.REAL,
                diseaseWarningHindi = diseaseText,
                speechTextHindi = speechText
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchJwtToken(): String? {
        return suspendCancellableCoroutine { continuation ->
            Amplify.Auth.fetchAuthSession(
                { session ->
                    val cognitoSession = session as? AWSCognitoAuthSession
                    if (cognitoSession == null) {
                        continuation.resume(null)
                        return@fetchAuthSession
                    }
                    val tokensResult = cognitoSession.userPoolTokensResult
                    if (tokensResult.type != AuthSessionResult.Type.SUCCESS) {
                        continuation.resume(null)
                        return@fetchAuthSession
                    }
                    val tokens = tokensResult.value
                    continuation.resume(tokens?.idToken ?: tokens?.accessToken)
                },
                { error ->
                    Log.w(tag, "Unable to fetch JWT token for Bedrock endpoint", error)
                    continuation.resume(null)
                }
            )
        }
    }
}

private fun parseGarbageCategory(raw: String?): GarbageCategory? {
    return when (raw.orEmpty().trim().uppercase()) {
        "PLASTIC" -> GarbageCategory.PLASTIC
        "PAPER" -> GarbageCategory.PAPER
        "METAL" -> GarbageCategory.METAL
        "GLASS" -> GarbageCategory.GLASS
        "ORGANIC" -> GarbageCategory.ORGANIC
        "E_WASTE", "EWASTE", "E-WASTE" -> GarbageCategory.E_WASTE
        else -> null
    }
}

private fun parseDemonType(raw: String?, category: GarbageCategory?): DemonType {
    val normalized = raw.orEmpty().trim().uppercase()
    return when {
        normalized == "E_WASTE" || normalized == "EWASTE" || normalized == "E-WASTE" -> DemonType.E_WASTE
        normalized == "ORGANIC" -> DemonType.ORGANIC
        category == GarbageCategory.E_WASTE -> DemonType.E_WASTE
        category == GarbageCategory.ORGANIC -> DemonType.ORGANIC
        else -> DemonType.PLASTIC
    }
}

private fun parseDemonKind(raw: String?): DemonKind? {
    return when (raw.orEmpty().trim().uppercase()) {
        "BACTERIA" -> DemonKind.BACTERIA
        "VIRUS" -> DemonKind.VIRUS
        "FUNGUS" -> DemonKind.FUNGUS
        else -> null
    }
}

private fun parseDemonMix(
    rawArray: JSONArray?,
    totalCount: Int,
    dominantKind: DemonKind
): List<DemonSpawn> {
    if (totalCount <= 0) return emptyList()
    if (rawArray == null) {
        return buildDemonMix(totalCount = totalCount, dominantKind = dominantKind)
    }

    val parsed = mutableListOf<DemonSpawn>()
    for (i in 0 until rawArray.length()) {
        val item = rawArray.optJSONObject(i) ?: continue
        val kind = parseDemonKind(item.optString("kind")) ?: continue
        val count = item.optInt("count", 0).coerceAtLeast(0)
        if (count > 0) {
            parsed += DemonSpawn(kind = kind, count = count)
        }
    }
    return normalizeDemonMix(
        proposedMix = parsed,
        totalCount = totalCount,
        dominantKind = dominantKind
    )
}

private fun defaultDemonCount(
    size: GarbageSize,
    binClosed: Boolean?,
    binOverflowing: Boolean?
): Int {
    val sizeBonus = when (size) {
        GarbageSize.SMALL -> 0
        GarbageSize.MEDIUM -> 1
        GarbageSize.LARGE -> 2
    }
    val closedBonus = if (binClosed == true) 1 else 0
    val overflowBonus = if (binOverflowing == true) 1 else 0
    return (2 + sizeBonus + closedBonus + overflowBonus).coerceIn(1, 8)
}

private fun fallbackBinIssue(
    recommendedBinNearby: Boolean,
    binClosed: Boolean?,
    binOverflowing: Boolean?,
    isGarbage: Boolean,
    language: AppLanguage
): String {
    if (language == AppLanguage.HINDI) {
        if (!isGarbage) return "कचरा कार्रवाई की जरूरत नहीं।"
        return when {
            binOverflowing == true -> "बिन भरा हुआ लग रहा है। पास का दूसरा बिन इस्तेमाल करें।"
            binClosed == true -> "बिन बंद है। निपटान से पहले सुरक्षित तरीके से खोलें।"
            !recommendedBinNearby -> "सही बिन पास में नहीं दिख रहा।"
            else -> "सही बिन पास में उपलब्ध है।"
        }
    }
    if (!isGarbage) return "No waste action needed."
    return when {
        binOverflowing == true -> "Bin looks overflowing. Use an alternate nearby bin."
        binClosed == true -> "Bin looks closed. Open safely before disposal."
        !recommendedBinNearby -> "Correct bin is not identified nearby."
        else -> "Correct bin is nearby."
    }
}

private fun fallbackActionPrompt(
    recommendedBinNearby: Boolean,
    isGarbage: Boolean,
    language: AppLanguage
): String {
    if (language == AppLanguage.HINDI) {
        if (!isGarbage) return "मिशन नहीं। वास्तविक कचरा मिलने पर शुरू होगा।"
        return if (recommendedBinNearby) {
            "कचरा उठाएं, पिक-अप कैप्चर करें, फिर बिन में डालकर डिस्पोज़ल कैप्चर करें।"
        } else {
            "पहले सही बिन खोजें, फिर पिक-अप और डिस्पोज़ल कैप्चर करें।"
        }
    }
    if (!isGarbage) return "No mission. Find real garbage to start."
    return if (recommendedBinNearby) {
        "Pick the garbage, capture pick-up, then throw in bin and capture disposal."
    } else {
        "Locate the correct bin first, then capture pick-up and disposal."
    }
}

private fun toJsonBins(bins: List<BinObservation>): JSONArray {
    val jsonArray = JSONArray()
    bins.forEach { bin ->
        jsonArray.put(
            JSONObject()
                .put("type", bin.type.name)
                .put("confidence", bin.confidence)
        )
    }
    return jsonArray
}

private fun firstNonBlank(values: List<String?>): String? {
    return values.firstNotNullOfOrNull { value ->
        value?.trim()?.takeIf { it.isNotBlank() }
    }
}

private fun defaultDiseaseWarning(language: AppLanguage): String {
    return if (language == AppLanguage.HINDI) {
        "कचरे का गलत निपटान संक्रमण और सांस की बीमारियों का खतरा बढ़ाता है।"
    } else {
        "Improper waste disposal increases risk of infection and respiratory disease."
    }
}

private fun postJson(
    url: String,
    payload: String,
    bearerToken: String?
): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 10_000
        readTimeout = 14_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        if (!bearerToken.isNullOrBlank()) {
            setRequestProperty("Authorization", "Bearer $bearerToken")
        }
    }
    OutputStreamWriter(connection.outputStream).use { it.write(payload) }
    val responseCode = connection.responseCode
    val body = if (responseCode in 200..299) {
        connection.inputStream.bufferedReader().use { it.readText() }
    } else {
        connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }
    if (responseCode !in 200..299) {
        throw IllegalStateException("Tip endpoint failed: HTTP $responseCode $body")
    }
    return body
}
