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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rivi.arwastewarrior.detection.SimulatedArAiDetectionService
import com.rivi.arwastewarrior.ui.AppPalette
import com.rivi.arwastewarrior.ui.screens.HomeScreen
import com.rivi.arwastewarrior.ui.screens.LoginScreen
import com.rivi.arwastewarrior.ui.screens.SignupScreen

private enum class AppScreen {
    LOGIN,
    SIGNUP,
    HOME
}

@Composable
@Preview
fun App() {
    var screen by remember { mutableStateOf(AppScreen.LOGIN) }
    val authService = remember { platformAuthService() }
    val detectionService = remember { SimulatedArAiDetectionService() }

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
                    AppScreen.LOGIN -> AuthContainer(
                        title = "Welcome Back",
                        subtitle = "Login to ARWasteWarriors"
                    ) {
                        LoginScreen(
                            authService = authService,
                            onCreateAccountClick = { screen = AppScreen.SIGNUP },
                            onLoginSuccess = { screen = AppScreen.HOME }
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
                        username = "vinamravinay12",
                        detectionService = detectionService,
                        onLogout = { screen = AppScreen.LOGIN }
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
