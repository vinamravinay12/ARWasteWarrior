package com.rivi.arwastewarrior.detection

interface GarbageDetectionService {
    suspend fun detectGarbage(
        liveScanResult: LiveScanResult? = null,
        language: AppLanguage = AppLanguage.ENGLISH
    ): DetectionResult
}
