package com.rivi.arwastewarrior.detection

interface GarbageDetectionService {
    suspend fun detectGarbage(): DetectionResult
}
