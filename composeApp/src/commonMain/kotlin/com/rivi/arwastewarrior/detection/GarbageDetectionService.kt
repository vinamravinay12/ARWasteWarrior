package com.rivi.arwastewarrior.detection

interface GarbageDetectionService {
    suspend fun scanGarbage(
        liveScanResult: LiveScanResult? = null,
        language: AppLanguage = AppLanguage.ENGLISH
    ): DetectionResult

    suspend fun scanBin(
        liveScanResult: LiveScanResult? = null,
        sceneHash: String? = null,
        language: AppLanguage = AppLanguage.ENGLISH
    ): BinScanResult

    suspend fun checkPickup(
        sceneHash: String,
        liveScanResult: LiveScanResult? = null,
        language: AppLanguage = AppLanguage.ENGLISH,
        motionPeak: Float = 0f,
        motionHits: Int = 0,
        durationMs: Int = 0
    ): PickupCheckResult

    suspend fun checkThrow(
        sceneHash: String,
        binSceneHash: String? = null,
        liveScanResult: LiveScanResult? = null,
        language: AppLanguage = AppLanguage.ENGLISH,
        motionPeak: Float = 0f,
        motionHits: Int = 0,
        durationMs: Int = 0,
        binDetected: Boolean = false,
        requestedDestroyCount: Int = 1
    ): ThrowCheckResult

    suspend fun detectGarbage(
        liveScanResult: LiveScanResult? = null,
        language: AppLanguage = AppLanguage.ENGLISH
    ): DetectionResult = scanGarbage(
        liveScanResult = liveScanResult,
        language = language
    )
}
