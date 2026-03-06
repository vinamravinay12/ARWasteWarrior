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
import java.security.MessageDigest
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

    private data class JwtTokens(
        val idToken: String?,
        val accessToken: String?
    )

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
                ).copy(source = "FALLBACK")
            }
            try {
                val sceneHash = computeSceneHash(
                    category = category,
                    language = language,
                    garbageSize = garbageSize,
                    binClosed = binClosed,
                    binOverflowing = binOverflowing,
                    rawLabels = rawLabels
                )
                val payload = JSONObject()
                    .put("eventType", "SCAN")
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
                    .put("sceneHash", sceneHash)
                    .toString()

                val tokens = fetchJwtTokens()
                val responseBody = postJsonWithTokenFallback(
                    url = endpointUrl,
                    payload = payload,
                    idToken = tokens.idToken,
                    accessToken = tokens.accessToken
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
                ).copy(source = "FALLBACK")
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
                ).copy(source = "FALLBACK")
            }
        }
    }

    override suspend fun analyzeBinEncounter(
        sceneHash: String?,
        language: AppLanguage,
        rawLabels: List<String>,
        detectedBins: List<BinObservation>,
        binClosed: Boolean?,
        binOverflowing: Boolean?
    ): BinAiEncounter {
        return withContext(Dispatchers.IO) {
            if (!bedrockEnabled || endpointUrl.isBlank()) {
                if (endpointUrl.isBlank()) {
                    bedrockEnabled = false
                }
                return@withContext fallback.analyzeBinEncounter(
                    sceneHash = sceneHash,
                    language = language,
                    rawLabels = rawLabels,
                    detectedBins = detectedBins,
                    binClosed = binClosed,
                    binOverflowing = binOverflowing
                )
            }
            try {
                val fallbackHash = sceneHash?.takeIf { it.isNotBlank() }
                    ?: computeBinSceneHash(
                        rawLabels = rawLabels,
                        detectedBins = detectedBins,
                        binClosed = binClosed,
                        binOverflowing = binOverflowing
                    )
                val payload = JSONObject()
                    .put("eventType", "BIN_SCAN")
                    .put("binSceneHash", fallbackHash)
                    .put("language", language.code)
                    .put("rawLabels", JSONArray(rawLabels.take(12)))
                    .put("detectedBins", toJsonBins(detectedBins))
                    .put("binClosed", binClosed)
                    .put("binOverflowing", binOverflowing)
                    .toString()

                val tokens = fetchJwtTokens()
                val responseBody = postJsonWithTokenFallback(
                    url = endpointUrl,
                    payload = payload,
                    idToken = tokens.idToken,
                    accessToken = tokens.accessToken
                )
                parseBinEncounter(
                    rawJson = responseBody,
                    inputSceneHash = fallbackHash,
                    language = language
                ) ?: fallback.analyzeBinEncounter(
                    sceneHash = fallbackHash,
                    language = language,
                    rawLabels = rawLabels,
                    detectedBins = detectedBins,
                    binClosed = binClosed,
                    binOverflowing = binOverflowing
                )
            } catch (e: Exception) {
                Log.w(tag, "Bedrock BIN_SCAN failed, using fallback", e)
                fallback.analyzeBinEncounter(
                    sceneHash = sceneHash,
                    language = language,
                    rawLabels = rawLabels,
                    detectedBins = detectedBins,
                    binClosed = binClosed,
                    binOverflowing = binOverflowing
                )
            }
        }
    }

    override suspend fun validatePickup(
        sceneHash: String,
        language: AppLanguage,
        category: GarbageCategory?,
        remainingDemons: Int,
        motionPeak: Float,
        motionHits: Int,
        durationMs: Int,
        rawLabels: List<String>
    ): PickupAiDecision {
        return withContext(Dispatchers.IO) {
            if (!bedrockEnabled || endpointUrl.isBlank()) {
                if (endpointUrl.isBlank()) {
                    bedrockEnabled = false
                }
                val reason = if (language == AppLanguage.HINDI) {
                    "AI pickup verification unavailable."
                } else {
                    "AI pickup verification unavailable."
                }
                return@withContext PickupAiDecision(
                    sceneHash = sceneHash,
                    pickupConfirmed = false,
                    pickupStrength = 0,
                    pendingPickupCount = 0,
                    reason = reason,
                    speechText = reason,
                    remainingDemons = remainingDemons,
                    sceneCleared = remainingDemons <= 0,
                    source = "UNAVAILABLE"
                )
            }
            try {
                val payload = JSONObject()
                    .put("eventType", "PICKUP_CHECK")
                    .put("sceneHash", sceneHash)
                    .put("language", language.code)
                    .put("category", category?.name ?: "UNKNOWN")
                    .put("remainingDemons", remainingDemons)
                    .put("pickupEvidence", JSONObject()
                        .put("motionPeak", motionPeak)
                        .put("motionHits", motionHits)
                        .put("durationMs", durationMs)
                    )
                    .put("rawLabels", JSONArray(rawLabels.take(12)))
                    .toString()

                val tokens = fetchJwtTokens()
                val responseBody = postJsonWithTokenFallback(
                    url = endpointUrl,
                    payload = payload,
                    idToken = tokens.idToken,
                    accessToken = tokens.accessToken
                )
                parsePickupDecision(
                    rawJson = responseBody,
                    sceneHash = sceneHash,
                    language = language
                ) ?: PickupAiDecision(
                    pickupConfirmed = false,
                    pickupStrength = 0,
                    pendingPickupCount = 0,
                    reason = if (language == AppLanguage.HINDI) {
                        "AI pickup verification failed."
                    } else {
                        "AI pickup verification failed."
                    },
                    speechText = if (language == AppLanguage.HINDI) {
                        "AI pickup verification failed."
                    } else {
                        "AI pickup verification failed."
                    },
                    remainingDemons = remainingDemons,
                    sceneCleared = remainingDemons <= 0,
                    sceneHash = sceneHash,
                    source = "ERROR"
                )
            } catch (e: Exception) {
                Log.w(tag, "Bedrock PICKUP_CHECK failed", e)
                val reason = if (language == AppLanguage.HINDI) {
                    "AI pickup verification failed."
                } else {
                    "AI pickup verification failed."
                }
                PickupAiDecision(
                    sceneHash = sceneHash,
                    pickupConfirmed = false,
                    pickupStrength = 0,
                    pendingPickupCount = 0,
                    reason = reason,
                    speechText = reason,
                    remainingDemons = remainingDemons,
                    sceneCleared = remainingDemons <= 0,
                    source = "ERROR"
                )
            }
        }
    }

    override suspend fun validateThrow(
        sceneHash: String,
        binSceneHash: String?,
        language: AppLanguage,
        category: GarbageCategory?,
        remainingDemons: Int,
        pendingPickupCount: Int,
        requestedDestroyCount: Int,
        motionPeak: Float,
        motionHits: Int,
        durationMs: Int,
        binDetected: Boolean,
        rawLabels: List<String>
    ): ThrowAiDecision {
        return withContext(Dispatchers.IO) {
            if (!bedrockEnabled || endpointUrl.isBlank()) {
                if (endpointUrl.isBlank()) {
                    bedrockEnabled = false
                }
                val reason = if (language == AppLanguage.HINDI) {
                    "AI throw verification unavailable."
                } else {
                    "AI throw verification unavailable."
                }
                return@withContext ThrowAiDecision(
                    sceneHash = sceneHash,
                    binSceneHash = binSceneHash.orEmpty(),
                    throwConfirmed = false,
                    destroyCount = 0,
                    destroyedDemons = 0,
                    reason = reason,
                    speechText = reason,
                    remainingDemons = remainingDemons,
                    sceneCleared = remainingDemons <= 0,
                    source = "UNAVAILABLE"
                )
            }
            try {
                val payload = JSONObject()
                    .put("eventType", "THROW_CHECK")
                    .put("sceneHash", sceneHash)
                    .put("binSceneHash", binSceneHash)
                    .put("language", language.code)
                    .put("category", category?.name ?: "UNKNOWN")
                    .put("binDetected", binDetected)
                    .put("requestedDestroyCount", requestedDestroyCount)
                    .put("throwEvidence", JSONObject()
                        .put("motionPeak", motionPeak)
                        .put("motionHits", motionHits)
                        .put("durationMs", durationMs)
                    )
                    .put("rawLabels", JSONArray(rawLabels.take(12)))
                    .toString()

                val tokens = fetchJwtTokens()
                val responseBody = postJsonWithTokenFallback(
                    url = endpointUrl,
                    payload = payload,
                    idToken = tokens.idToken,
                    accessToken = tokens.accessToken
                )
                parseThrowDecision(
                    rawJson = responseBody,
                    sceneHash = sceneHash,
                    language = language
                ) ?: ThrowAiDecision(
                    throwConfirmed = false,
                    destroyCount = 0,
                    destroyedDemons = 0,
                    reason = if (language == AppLanguage.HINDI) {
                        "AI throw verification failed."
                    } else {
                        "AI throw verification failed."
                    },
                    speechText = if (language == AppLanguage.HINDI) {
                        "AI throw verification failed."
                    } else {
                        "AI throw verification failed."
                    },
                    remainingDemons = remainingDemons,
                    sceneCleared = remainingDemons <= 0,
                    sceneHash = sceneHash,
                    binSceneHash = binSceneHash.orEmpty(),
                    source = "ERROR"
                )
            } catch (e: Exception) {
                Log.w(tag, "Bedrock THROW_CHECK failed", e)
                val reason = if (language == AppLanguage.HINDI) {
                    "AI throw verification failed."
                } else {
                    "AI throw verification failed."
                }
                ThrowAiDecision(
                    sceneHash = sceneHash,
                    binSceneHash = binSceneHash.orEmpty(),
                    throwConfirmed = false,
                    destroyCount = 0,
                    destroyedDemons = 0,
                    reason = reason,
                    speechText = reason,
                    remainingDemons = remainingDemons,
                    sceneCleared = remainingDemons <= 0,
                    source = "ERROR"
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
            val obj = parseBackendBody(rawJson) ?: return null
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
                speechTextHindi = speechText,
                sceneHash = obj.optString("sceneHash").orEmpty(),
                remainingDemons = obj.optInt("remainingDemons", -1).takeIf { it >= 0 },
                recommendedDestroyCount = obj.optInt("recommendedDestroyCount", -1).takeIf { it >= 0 },
                pendingPickupCount = obj.optInt("pendingPickupCount", 0).coerceAtLeast(0),
                seenBefore = obj.optBoolean("seenBefore", false),
                source = obj.optString("source").takeIf { it.isNotBlank() } ?: "BEDROCK"
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseBinEncounter(
        rawJson: String,
        inputSceneHash: String,
        language: AppLanguage
    ): BinAiEncounter? {
        return try {
            val obj = parseBackendBody(rawJson) ?: return null
            val detected = obj.optBoolean("binDetected", false)
            val type = parseBinType(obj.optString("binType"))
            val message = obj.optString("message")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: if (detected) {
                    if (language == AppLanguage.HINDI) "डस्टबिन मिला।" else "Bin detected."
                } else {
                    if (language == AppLanguage.HINDI) "डस्टबिन नहीं मिला।" else "Bin not detected."
                }
            val speech = firstNonBlank(
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
            ) ?: message
            val parsedBinClosed = if (obj.has("binClosed") && !obj.isNull("binClosed")) {
                obj.optBoolean("binClosed", false)
            } else {
                null
            }
            val parsedBinOverflowing = if (obj.has("binOverflowing") && !obj.isNull("binOverflowing")) {
                obj.optBoolean("binOverflowing", false)
            } else {
                null
            }
            BinAiEncounter(
                binDetected = detected,
                binType = type,
                binClosed = parsedBinClosed,
                binOverflowing = parsedBinOverflowing,
                message = message,
                speechText = speech,
                binSceneHash = obj.optString("binSceneHash").ifBlank { inputSceneHash },
                seenBefore = obj.optBoolean("seenBefore", false),
                source = obj.optString("source").orEmpty()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePickupDecision(
        rawJson: String,
        sceneHash: String,
        language: AppLanguage
    ): PickupAiDecision? {
        return try {
            val obj = parseBackendBody(rawJson) ?: return null
            val confirmed = obj.optBoolean("pickupConfirmed", false)
            val strength = obj.optInt("pickupStrength", 0).coerceAtLeast(0)
            val reason = obj.optString("reason")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: if (confirmed) {
                    if (language == AppLanguage.HINDI) "पिकअप कन्फर्म हुआ।" else "Pickup confirmed."
                } else {
                    if (language == AppLanguage.HINDI) "पिकअप कन्फर्म नहीं हुआ।" else "Pickup not confirmed."
                }
            val speech = firstNonBlank(
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
            ) ?: reason
            PickupAiDecision(
                pickupConfirmed = confirmed,
                pickupStrength = strength,
                pendingPickupCount = obj.optInt("pendingPickupCount", strength).coerceAtLeast(0),
                reason = reason,
                speechText = speech,
                remainingDemons = obj.optInt("remainingDemons", 0).coerceAtLeast(0),
                sceneCleared = obj.optBoolean("sceneCleared", false),
                sceneHash = obj.optString("sceneHash").ifBlank { sceneHash },
                source = obj.optString("source").orEmpty()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseThrowDecision(
        rawJson: String,
        sceneHash: String,
        language: AppLanguage
    ): ThrowAiDecision? {
        return try {
            val obj = parseBackendBody(rawJson) ?: return null
            val confirmed = obj.optBoolean("throwConfirmed", false)
            val destroyCount = obj.optInt("destroyCount", 0).coerceAtLeast(0)
            val reason = obj.optString("reason")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: if (confirmed) {
                    if (language == AppLanguage.HINDI) "थ्रो कन्फर्म हुआ।" else "Throw confirmed."
                } else {
                    if (language == AppLanguage.HINDI) "थ्रो कन्फर्म नहीं हुआ।" else "Throw not confirmed."
                }
            val speech = firstNonBlank(
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
            ) ?: reason
            ThrowAiDecision(
                throwConfirmed = confirmed,
                destroyCount = destroyCount,
                destroyedDemons = obj.optInt("destroyedDemons", destroyCount).coerceAtLeast(0),
                reason = reason,
                speechText = speech,
                remainingDemons = obj.optInt("remainingDemons", 0).coerceAtLeast(0),
                sceneCleared = obj.optBoolean("sceneCleared", false),
                sceneHash = obj.optString("sceneHash").ifBlank { sceneHash },
                binSceneHash = obj.optString("binSceneHash").orEmpty(),
                source = obj.optString("source").orEmpty()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseBackendBody(rawJson: String): JSONObject? {
        return try {
            val root = JSONObject(rawJson)
            if (root.has("body") && root.opt("body") is String) {
                JSONObject(root.optString("body"))
            } else {
                root
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchJwtTokens(): JwtTokens {
        return suspendCancellableCoroutine { continuation ->
            Amplify.Auth.fetchAuthSession(
                { session ->
                    val cognitoSession = session as? AWSCognitoAuthSession
                    if (cognitoSession == null) {
                        continuation.resume(JwtTokens(idToken = null, accessToken = null))
                        return@fetchAuthSession
                    }
                    val tokensResult = cognitoSession.userPoolTokensResult
                    if (tokensResult.type != AuthSessionResult.Type.SUCCESS) {
                        continuation.resume(JwtTokens(idToken = null, accessToken = null))
                        return@fetchAuthSession
                    }
                    val tokens = tokensResult.value
                    continuation.resume(
                        JwtTokens(
                            idToken = tokens?.idToken,
                            accessToken = tokens?.accessToken
                        )
                    )
                },
                { error ->
                    Log.w(tag, "Unable to fetch JWT token for Bedrock endpoint", error)
                    continuation.resume(JwtTokens(idToken = null, accessToken = null))
                }
            )
        }
    }
}

private fun postJsonWithTokenFallback(
    url: String,
    payload: String,
    idToken: String?,
    accessToken: String?
): String {
    val primary = idToken?.takeIf { it.isNotBlank() } ?: accessToken
    val secondary = when {
        primary == null -> null
        primary == idToken -> accessToken
        else -> idToken
    }?.takeIf { it.isNotBlank() && it != primary }

    return try {
        postJson(url = url, payload = payload, bearerToken = primary)
    } catch (e: IllegalStateException) {
        val shouldRetryWithSecondary = e.message?.contains("HTTP 401") == true && secondary != null
        if (!shouldRetryWithSecondary) throw e
        postJson(url = url, payload = payload, bearerToken = secondary)
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

private fun parseBinType(raw: String?): BinType {
    return when (raw.orEmpty().trim().uppercase().replace("-", "_")) {
        "PLASTIC" -> BinType.PLASTIC
        "PAPER" -> BinType.PAPER
        "METAL" -> BinType.METAL
        "GLASS" -> BinType.GLASS
        "ORGANIC" -> BinType.ORGANIC
        "E_WASTE", "EWASTE" -> BinType.E_WASTE
        "GENERAL" -> BinType.GENERAL
        else -> BinType.UNKNOWN
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

// Stable 16-char hex fingerprint used as backend cache key.
// Includes language and bin context to avoid cross-context cache collisions.
private fun computeSceneHash(
    category: GarbageCategory,
    language: AppLanguage,
    garbageSize: GarbageSize,
    binClosed: Boolean?,
    binOverflowing: Boolean?,
    rawLabels: List<String>
): String {
    val input = buildString {
        append(category.name)
        append("::")
        append(language.code)
        append("::")
        append(garbageSize.name)
        append("::")
        append(binClosed ?: "u")
        append("::")
        append(binOverflowing ?: "u")
        append("::")
        append(rawLabels.sorted().take(5).joinToString(","))
    }
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }.take(16)
}

private fun computeBinSceneHash(
    rawLabels: List<String>,
    detectedBins: List<BinObservation>,
    binClosed: Boolean?,
    binOverflowing: Boolean?
): String {
    val input = buildString {
        append(detectedBins.map { it.type.name }.sorted().joinToString(","))
        append("::")
        append(binClosed ?: "u")
        append("::")
        append(binOverflowing ?: "u")
        append("::")
        append(rawLabels.sorted().take(5).joinToString(","))
    }
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }.take(16)
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
