package com.rivi.arwastewarrior.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rivi.arwastewarrior.GameSession
import com.rivi.arwastewarrior.detection.AppLanguage

@Composable
fun HomeScreen(
    username: String,
    selectedLanguage: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    onPlayGame: () -> Unit,
    session: GameSession = GameSession(),
    onLogout: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color(0xFF06B88E), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        username.take(1).uppercase(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = username,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            TextButton(onClick = onLogout) {
                Text(
                    if (selectedLanguage == AppLanguage.HINDI) "लॉगआउट" else "Logout",
                    color = Color(0xFFFF6F6F)
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF123F4E)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    if (selectedLanguage == AppLanguage.HINDI) "भाषा" else "Language",
                    color = Color(0xFF9DD9CF),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LanguageButton(
                        language = AppLanguage.ENGLISH,
                        selectedLanguage = selectedLanguage,
                        onLanguageChanged = onLanguageChanged
                    )
                    LanguageButton(
                        language = AppLanguage.HINDI,
                        selectedLanguage = selectedLanguage,
                        onLanguageChanged = onLanguageChanged
                    )
                }
                Text(
                    if (selectedLanguage == AppLanguage.HINDI) "रियल गेमप्ले" else "Real Gameplay",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
                if (session.itemsDisposed > 0) {
                    SessionStatsCard(session, selectedLanguage)
                }
                Button(
                    onClick = onPlayGame,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4E00)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (selectedLanguage == AppLanguage.HINDI) "गेम शुरू करें" else "Play Game")
                }
            }
        }
    }
}

@Composable
private fun SessionStatsCard(session: GameSession, selectedLanguage: AppLanguage) {
    val hi = selectedLanguage == AppLanguage.HINDI
    androidx.compose.foundation.layout.Row(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .background(Color(0x22FFFFFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatItem(value = "${session.score}", label = if (hi) "अंक" else "Score", color = Color.White)
        StatItem(value = "${session.demonsDefeated}", label = if (hi) "डेमन हराए" else "Demons", color = Color(0xFFFF6B35))
        StatItem(value = "${session.itemsDisposed}", label = if (hi) "साफ किया" else "Cleaned", color = Color(0xFF07C49A))
        StatItem(value = formatSessionKg(session.co2SavedKg), label = "CO₂", color = Color(0xFF9DD9CF))
    }
}

@Composable
private fun StatItem(value: String, label: String, color: Color) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(value, color = color, style = MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(label, color = Color(0xFF9DD9CF), style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatSessionKg(kg: Double): String {
    val grams = (kg * 1000).toInt()
    return if (grams < 1000) "${grams}g" else "${grams / 1000}.${(grams % 1000) / 100}kg"
}

@Composable
private fun LanguageButton(
    language: AppLanguage,
    selectedLanguage: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit
) {
    val isSelected = language == selectedLanguage
    Button(
        onClick = { onLanguageChanged(language) },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) Color(0xFF07C49A) else Color(0xFF1D5A6D),
            contentColor = if (isSelected) Color(0xFF062720) else Color.White
        )
    ) {
        Text(language.label)
    }
}
