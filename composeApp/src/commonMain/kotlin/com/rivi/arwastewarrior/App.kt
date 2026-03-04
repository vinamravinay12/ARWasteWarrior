package com.rivi.arwastewarrior

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rivi.arwastewarrior.detection.AppLanguage
import com.rivi.arwastewarrior.detection.platformGarbageAiTipService
import com.rivi.arwastewarrior.detection.SimulatedArAiDetectionService
import com.rivi.arwastewarrior.ui.AppPalette
import com.rivi.arwastewarrior.ui.screens.HomeScreen
import com.rivi.arwastewarrior.ui.screens.LoginScreen
import com.rivi.arwastewarrior.ui.screens.PlayGameScreen
import com.rivi.arwastewarrior.ui.screens.SignupScreen
import com.rivi.arwastewarrior.GameSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class AppScreen {
    LOADING,
    LOGIN,
    SIGNUP,
    HOME,
    PLAY_GAME
}

@Composable
@Preview
fun App() {
    var screen by remember { mutableStateOf(AppScreen.LOADING) }
    var activeUsername by remember { mutableStateOf("Warrior") }
    var selectedLanguage by remember { mutableStateOf(AppLanguage.ENGLISH) }
    var gameSession by remember { mutableStateOf(GameSession()) }
    val authService = remember { platformAuthService() }
    val aiTipService = remember { platformGarbageAiTipService() }
    val detectionService = remember(aiTipService) { SimulatedArAiDetectionService(aiTipService) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(authService) {
        val signedIn = authService.isUserSignedIn()
        if (signedIn) {
            activeUsername = authService.getSignedInUsername().orEmpty().ifBlank { "Warrior" }
            screen = AppScreen.HOME
        } else {
            screen = AppScreen.LOGIN
        }
    }

    LaunchedEffect(screen, authService) {
        if (screen != AppScreen.HOME && screen != AppScreen.PLAY_GAME) return@LaunchedEffect
        while (isActive && (screen == AppScreen.HOME || screen == AppScreen.PLAY_GAME)) {
            delay(10 * 60 * 1000L)
            val stillSignedIn = authService.refreshSession()
            if (!stillSignedIn) {
                activeUsername = "Warrior"
                screen = AppScreen.LOGIN
            }
        }
    }

    MaterialTheme {
        Scaffold { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(AppPalette.backgroundTop, AppPalette.backgroundBottom)
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                when (screen) {
                    AppScreen.LOADING -> AuthContainer(
                        title = "Checking Session",
                        subtitle = "Restoring your warrior login"
                    ) {
                        Text(
                            text = "Please wait...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppPalette.textMuted
                        )
                    }

                    AppScreen.LOGIN -> AuthContainer(
                        title = "Welcome Back",
                        subtitle = "Login to ARWasteWarriors"
                    ) {
                        LoginScreen(
                            authService = authService,
                            onCreateAccountClick = { screen = AppScreen.SIGNUP },
                            onLoginSuccess = {
                                scope.launch {
                                    activeUsername = authService.getSignedInUsername().orEmpty().ifBlank { "Warrior" }
                                    screen = AppScreen.HOME
                                }
                            }
                        )
                    }

                    AppScreen.SIGNUP -> AuthContainer(
                        title = "Create Account",
                        subtitle = "Start your cleanup journey"
                    ) {
                        SignupScreen(
                            authService = authService,
                            onLoginClick = { screen = AppScreen.LOGIN },
                            onVerified = { screen = AppScreen.LOGIN }
                        )
                    }

                    AppScreen.HOME -> HomeScreen(
                        username = activeUsername,
                        selectedLanguage = selectedLanguage,
                        onLanguageChanged = { selectedLanguage = it },
                        onPlayGame = { screen = AppScreen.PLAY_GAME },
                        session = gameSession,
                        onLogout = {
                            scope.launch {
                                authService.signOut()
                                activeUsername = "Warrior"
                                gameSession = GameSession()
                                screen = AppScreen.LOGIN
                            }
                        }
                    )

                    AppScreen.PLAY_GAME -> PlayGameScreen(
                        detectionService = detectionService,
                        selectedLanguage = selectedLanguage,
                        session = gameSession,
                        onSessionUpdate = { gameSession = it },
                        onBack = { screen = AppScreen.HOME }
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthContainer(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ARWasteWarriors",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Join the battle for a cleaner planet",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF8DD6C8)
        )
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = AppPalette.surfaceCard)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppPalette.textMuted
                )
                Spacer(modifier = Modifier.height(18.dp))
                content()
            }
        }
    }
}
