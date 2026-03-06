package com.rivi.arwastewarrior.detection

class SimulatedArAiDetectionService(
    private val aiTipService: GarbageAiTipService = RuleBasedGarbageAiTipService()
) : GarbageDetectionService {
    // Session-level cache for SCAN responses.
    private val sessionCache = HashMap<String, DetectionResult>()
    private val sceneState = HashMap<String, SceneState>()

    private data class SceneState(
        var remainingDemons: Int,
        var pendingPickupCount: Int
    )

    private fun cacheKey(scan: LiveScanResult, language: AppLanguage): String? {
        val cat = scan.garbageCategory ?: return null
        val labels = scan.rawLabels.sorted().take(4).joinToString(",")
        val bins = scan.detectedBins.map { it.type.name }.sorted().take(3).joinToString(",")
        return buildString {
            append(cat.name)
            append("::")
            append(language.code)
            append("::")
            append(scan.garbageSize.name)
            append("::")
            append(scan.isBinClosed ?: "u")
            append("::")
            append(scan.isBinOverflowing ?: "u")
            append("::")
            append(bins)
            append("::")
            append(labels)
        }
    }

    private fun fallbackSceneHash(scan: LiveScanResult): String {
        val category = scan.garbageCategory?.name ?: "NONE"
        val labels = scan.rawLabels.sorted().take(5).joinToString("|")
        return "$category::${scan.garbageSize.name}::$labels"
    }

    private fun stateFor(sceneHash: String, defaultDemons: Int = 0): SceneState {
        return sceneState.getOrPut(sceneHash) {
            SceneState(
                remainingDemons = defaultDemons.coerceAtLeast(0),
                pendingPickupCount = 0
            )
        }
    }

    override suspend fun scanGarbage(
        liveScanResult: LiveScanResult?,
        language: AppLanguage
    ): DetectionResult {
        val scan = liveScanResult ?: return nonGarbageResult(
            confidence = 0,
            detectedBins = emptyList(),
            aiTipService = aiTipService,
            language = language
        )

        val preferredCategory = scan.garbageCategory ?: GarbageCategory.UNKNOWN
        val key = cacheKey(scan, language)
        if (key != null) {
            sessionCache[key]?.let { cached ->
                val sceneHash = cached.sceneHash.ifBlank { fallbackSceneHash(scan) }
                val state = stateFor(sceneHash, cached.remainingDemons)
                val remaining = state.remainingDemons.coerceAtLeast(0)
                val hasGarbage = cached.isGarbage && remaining > 0
                return cached.copy(
                    sceneHash = sceneHash,
                    remainingDemons = remaining,
                    isGarbage = hasGarbage,
                    demonCount = if (hasGarbage) remaining else 0,
                    demonsDefeatedGain = if (hasGarbage) remaining else 0,
                    recommendedDestroyCount = if (hasGarbage) minOf(3, remaining) else 0,
                    pendingPickupCount = state.pendingPickupCount
                )
            }
        }

        val recommendedBin = if (preferredCategory != GarbageCategory.UNKNOWN) {
            recommendedBinType(preferredCategory)
        } else {
            BinType.GENERAL
        }
        val recommendedBinNearby = preferredCategory != GarbageCategory.UNKNOWN &&
            scan.detectedBins.any { observation ->
                observation.type == recommendedBin || observation.type == BinType.GENERAL
            }

        val aiEncounter = aiTipService.analyzeEncounter(
            category = preferredCategory,
            confidence = scan.confidence.coerceIn(0, 99),
            recommendedBinNearby = recommendedBinNearby,
            language = language,
            garbageSize = scan.garbageSize,
            binClosed = scan.isBinClosed,
            binOverflowing = scan.isBinOverflowing,
            likelyGarbage = scan.isLikelyGarbage,
            rawLabels = scan.rawLabels,
            detectedBins = scan.detectedBins
        )

        val resolvedCategory = aiEncounter.resolvedCategory ?: preferredCategory
        val derivedSceneHash = aiEncounter.sceneHash.ifBlank {
            key ?: fallbackSceneHash(scan)
        }
        val state = stateFor(
            sceneHash = derivedSceneHash,
            defaultDemons = aiEncounter.demonCount.coerceAtLeast(0)
        )

        if (!aiEncounter.isGarbage || resolvedCategory == GarbageCategory.UNKNOWN) {
            val nonGarbage = DetectionResult(
                category = GarbageCategory.UNKNOWN,
                isGarbage = false,
                confidence = scan.confidence.coerceIn(0, 99),
                aiTip = aiEncounter.tip,
                binIssue = aiEncounter.binIssue,
                actionPrompt = aiEncounter.actionPrompt,
                co2SavedKg = 0.0,
                demonsDefeatedGain = 0,
                detectedBins = scan.detectedBins.map { it.type.label },
                isRecommendedBinNearby = false,
                demonCount = 0,
                demonType = aiEncounter.demonType,
                demonMix = emptyList(),
                gameModeOptions = listOf(GameModeOption.REAL),
                recommendedMode = GameModeOption.REAL,
                diseaseWarningHindi = aiEncounter.diseaseWarningHindi,
                speechTextHindi = aiEncounter.speechTextHindi,
                sceneHash = derivedSceneHash,
                remainingDemons = 0,
                recommendedDestroyCount = 0,
                pendingPickupCount = state.pendingPickupCount,
                seenBefore = aiEncounter.seenBefore,
                source = aiEncounter.source
            )
            if (key != null) {
                if (sessionCache.size >= 20) sessionCache.clear()
                sessionCache[key] = nonGarbage
            }
            state.remainingDemons = 0
            return nonGarbage
        }

        val correctedRecommendedBin = recommendedBinType(resolvedCategory)
        val correctedBinNearby = scan.detectedBins.any { observation ->
            observation.type == correctedRecommendedBin || observation.type == BinType.GENERAL
        }

        val backendRemaining = aiEncounter.remainingDemons
        val actualRemaining = when {
            backendRemaining != null -> backendRemaining.coerceAtLeast(0)
            state.remainingDemons > 0 -> state.remainingDemons
            else -> aiEncounter.demonCount.coerceAtLeast(1)
        }
        state.remainingDemons = actualRemaining

        val hasGarbage = actualRemaining > 0
        val visibleCount = if (hasGarbage) actualRemaining else 0
        val result = DetectionResult(
            category = resolvedCategory,
            isGarbage = hasGarbage,
            confidence = scan.confidence.coerceIn(55, 99),
            aiTip = aiEncounter.tip,
            binIssue = aiEncounter.binIssue,
            actionPrompt = aiEncounter.actionPrompt,
            co2SavedKg = co2SavedFor(resolvedCategory),
            demonsDefeatedGain = visibleCount,
            detectedBins = scan.detectedBins.map { it.type.label },
            isRecommendedBinNearby = correctedBinNearby,
            demonCount = visibleCount,
            demonType = aiEncounter.demonType,
            demonMix = if (hasGarbage) {
                normalizeDemonMix(
                    proposedMix = aiEncounter.demonMix,
                    totalCount = visibleCount,
                    dominantKind = defaultDominantKind(resolvedCategory)
                )
            } else {
                emptyList()
            },
            gameModeOptions = listOf(GameModeOption.REAL),
            recommendedMode = GameModeOption.REAL,
            diseaseWarningHindi = aiEncounter.diseaseWarningHindi,
            speechTextHindi = aiEncounter.speechTextHindi,
            sceneHash = derivedSceneHash,
            remainingDemons = actualRemaining,
            recommendedDestroyCount = if (hasGarbage) {
                aiEncounter.recommendedDestroyCount ?: minOf(3, visibleCount)
            } else {
                0
            },
            pendingPickupCount = state.pendingPickupCount,
            seenBefore = aiEncounter.seenBefore,
            source = aiEncounter.source
        )

        if (key != null) {
            if (sessionCache.size >= 20) sessionCache.clear()
            sessionCache[key] = result
        }
        return result
    }

    override suspend fun scanBin(
        liveScanResult: LiveScanResult?,
        sceneHash: String?,
        language: AppLanguage
    ): BinScanResult {
        val scan = liveScanResult ?: return BinScanResult(
            binDetected = false,
            message = if (language == AppLanguage.HINDI) {
                "डस्टबिन नहीं मिला।"
            } else {
                "Bin not detected."
            }
        )

        val encounter = aiTipService.analyzeBinEncounter(
            sceneHash = sceneHash,
            language = language,
            rawLabels = scan.rawLabels,
            detectedBins = scan.detectedBins,
            binClosed = scan.isBinClosed,
            binOverflowing = scan.isBinOverflowing
        )

        return BinScanResult(
            binDetected = encounter.binDetected,
            binType = encounter.binType,
            binClosed = encounter.binClosed,
            binOverflowing = encounter.binOverflowing,
            message = encounter.message,
            speechText = encounter.speechText,
            binSceneHash = encounter.binSceneHash,
            seenBefore = encounter.seenBefore,
            source = encounter.source
        )
    }

    override suspend fun checkPickup(
        sceneHash: String,
        liveScanResult: LiveScanResult?,
        language: AppLanguage,
        motionPeak: Float,
        motionHits: Int,
        durationMs: Int
    ): PickupCheckResult {
        val state = stateFor(sceneHash)
        val decision = aiTipService.validatePickup(
            sceneHash = sceneHash,
            language = language,
            category = liveScanResult?.garbageCategory,
            remainingDemons = state.remainingDemons,
            motionPeak = motionPeak,
            motionHits = motionHits,
            durationMs = durationMs,
            rawLabels = liveScanResult?.rawLabels.orEmpty()
        )

        val pending = if (decision.pickupConfirmed) {
            decision.pendingPickupCount.coerceAtLeast(decision.pickupStrength).coerceAtLeast(1)
        } else {
            0
        }
        state.pendingPickupCount = pending

        return PickupCheckResult(
            pickupConfirmed = decision.pickupConfirmed,
            pickupStrength = decision.pickupStrength.coerceAtLeast(0),
            pendingPickupCount = pending,
            reason = decision.reason,
            speechText = decision.speechText,
            remainingDemons = decision.remainingDemons.coerceAtLeast(state.remainingDemons),
            sceneCleared = decision.sceneCleared || state.remainingDemons <= 0,
            sceneHash = sceneHash,
            source = decision.source
        )
    }

    override suspend fun checkThrow(
        sceneHash: String,
        binSceneHash: String?,
        liveScanResult: LiveScanResult?,
        language: AppLanguage,
        motionPeak: Float,
        motionHits: Int,
        durationMs: Int,
        binDetected: Boolean,
        requestedDestroyCount: Int
    ): ThrowCheckResult {
        val state = stateFor(sceneHash)
        val decision = aiTipService.validateThrow(
            sceneHash = sceneHash,
            binSceneHash = binSceneHash,
            language = language,
            category = liveScanResult?.garbageCategory,
            remainingDemons = state.remainingDemons,
            pendingPickupCount = state.pendingPickupCount,
            requestedDestroyCount = requestedDestroyCount,
            motionPeak = motionPeak,
            motionHits = motionHits,
            durationMs = durationMs,
            binDetected = binDetected,
            rawLabels = liveScanResult?.rawLabels.orEmpty()
        )

        val destroyCap = minOf(
            state.remainingDemons.coerceAtLeast(0),
            state.pendingPickupCount.coerceAtLeast(1),
            requestedDestroyCount.coerceAtLeast(1),
            3
        )
        val destroyed = if (decision.throwConfirmed) {
            val fromDecision = decision.destroyedDemons.takeIf { it > 0 } ?: decision.destroyCount
            fromDecision.coerceIn(1, destroyCap.coerceAtLeast(1))
        } else {
            0
        }
        val remaining = if (decision.throwConfirmed) {
            decision.remainingDemons.takeIf { it >= 0 }
                ?: (state.remainingDemons - destroyed)
        } else {
            state.remainingDemons
        }.coerceAtLeast(0)

        state.remainingDemons = remaining
        state.pendingPickupCount = 0
        updateCachedScene(sceneHash, remaining)

        return ThrowCheckResult(
            throwConfirmed = decision.throwConfirmed,
            destroyCount = decision.destroyCount.coerceAtLeast(0),
            destroyedDemons = destroyed,
            reason = decision.reason,
            speechText = decision.speechText,
            remainingDemons = remaining,
            sceneCleared = remaining <= 0,
            sceneHash = sceneHash,
            source = decision.source
        )
    }

    private fun updateCachedScene(sceneHash: String, remaining: Int) {
        val entries = sessionCache.entries.toList()
        entries.forEach { (key, value) ->
            if (value.sceneHash == sceneHash) {
                val hasGarbage = remaining > 0
                sessionCache[key] = value.copy(
                    isGarbage = hasGarbage,
                    remainingDemons = remaining,
                    demonCount = if (hasGarbage) remaining else 0,
                    demonsDefeatedGain = if (hasGarbage) remaining else 0,
                    recommendedDestroyCount = if (hasGarbage) minOf(3, remaining) else 0,
                    pendingPickupCount = 0
                )
            }
        }
    }

    private suspend fun nonGarbageResult(
        confidence: Int,
        detectedBins: List<String>,
        aiTipService: GarbageAiTipService,
        language: AppLanguage
    ): DetectionResult {
        val aiEncounter = aiTipService.analyzeEncounter(
            category = GarbageCategory.UNKNOWN,
            confidence = confidence,
            recommendedBinNearby = false,
            language = language,
            likelyGarbage = false
        )
        return DetectionResult(
            category = GarbageCategory.UNKNOWN,
            isGarbage = false,
            confidence = confidence,
            aiTip = aiEncounter.tip,
            binIssue = aiEncounter.binIssue,
            actionPrompt = aiEncounter.actionPrompt,
            co2SavedKg = 0.0,
            demonsDefeatedGain = 0,
            detectedBins = detectedBins,
            isRecommendedBinNearby = false,
            demonCount = 0,
            demonType = aiEncounter.demonType,
            demonMix = emptyList(),
            gameModeOptions = listOf(GameModeOption.REAL),
            recommendedMode = GameModeOption.REAL,
            diseaseWarningHindi = aiEncounter.diseaseWarningHindi,
            speechTextHindi = aiEncounter.speechTextHindi,
            remainingDemons = 0,
            recommendedDestroyCount = 0
        )
    }

    private fun co2SavedFor(category: GarbageCategory): Double {
        return when (category) {
            GarbageCategory.UNKNOWN -> 0.0
            GarbageCategory.PLASTIC -> 0.32
            GarbageCategory.PAPER -> 0.18
            GarbageCategory.METAL -> 0.52
            GarbageCategory.GLASS -> 0.27
            GarbageCategory.ORGANIC -> 0.14
            GarbageCategory.E_WASTE -> 0.61
        }
    }
}
