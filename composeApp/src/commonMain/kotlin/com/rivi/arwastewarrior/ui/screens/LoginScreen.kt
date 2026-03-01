package com.rivi.arwastewarrior.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rivi.arwastewarrior.AuthService
import com.rivi.arwastewarrior.ui.AppPalette
import com.rivi.arwastewarrior.ui.components.AuthTextField
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    authService: AuthService,
    onCreateAccountClick: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    var signInId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AuthTextField(
        label = "Email / Phone / Username",
        value = signInId,
        onValueChange = { signInId = it }
    )
    Spacer(modifier = Modifier.height(12.dp))
    AuthTextField(
        label = "Password",
        value = password,
        onValueChange = { password = it },
        keyboardType = KeyboardType.Password,
        isPassword = true,
        passwordVisible = passwordVisible,
        onTogglePassword = { passwordVisible = !passwordVisible }
    )

    Spacer(modifier = Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(
            onClick = {
                scope.launch {
                    feedback = authService.resetPassword(signInId).message
                }
            }
        ) {
            Text("Forgot password?", color = AppPalette.accentGreen)
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            scope.launch {
                val response = authService.signIn(signInId, password)
                feedback = response.message
                if (response.success) {
                    onLoginSuccess()
                }
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = AppPalette.accentGreen)
    ) {
        Text(text = "Login to Battle", color = Color(0xFF042C23))
    }

    feedback?.let {
        Spacer(modifier = Modifier.height(10.dp))
        Text(it, color = Color(0xFFCFEFED), style = MaterialTheme.typography.bodySmall)
    }

    Spacer(modifier = Modifier.height(16.dp))
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onCreateAccountClick
    ) {
        Text("New warrior? Create Account", color = Color.White)
    }
}
