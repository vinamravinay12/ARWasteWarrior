package com.rivi.arwastewarrior

data class GameSession(
    val score: Int = 0,
    val demonsDefeated: Int = 0,
    val itemsDisposed: Int = 0,
    val co2SavedKg: Double = 0.0
) {
    fun addVictory(demonCount: Int, co2Kg: Double): GameSession = copy(
        score = score + demonCount * 15 + 10,
        demonsDefeated = demonsDefeated + demonCount,
        itemsDisposed = itemsDisposed + 1,
        co2SavedKg = co2SavedKg + co2Kg
    )
}
