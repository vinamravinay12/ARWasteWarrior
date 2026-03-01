package com.rivi.arwastewarrior.ui.screens

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
import com.rivi.arwastewarrior.SignUpInput
import com.rivi.arwastewarrior.ui.AppPalette
import com.rivi.arwastewarrior.ui.components.AuthTextField
import com.rivi.arwastewarrior.ui.components.PasswordRules
import com.rivi.arwastewarrior.ui.components.allowedGenders
import com.rivi.arwastewarrior.ui.components.dobRegex
import com.rivi.arwastewarrior.ui.components.isPasswordPolicyValid
import com.rivi.arwastewarrior.ui.components.phoneRegex
import kotlinx.coroutines.launch

@Composable
fun SignupScreen(
    authService: AuthService,
    onLoginClick: () -> Unit,
    onVerified: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var preferredUsername by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var confirmationCode by remember { mutableStateOf("") }
    var needsConfirmation by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AuthTextField(label = "Name", value = name, onValueChange = { name = it })
    Spacer(modifier = Modifier.height(12.dp))
    AuthTextField(
        label = "Username (preferred_username)",
        value = preferredUsername,
        onValueChange = { preferredUsername = it }
    )
    Spacer(modifier = Modifier.height(12.dp))
    AuthTextField(
        label = "Phone Number (E.164, e.g. +9198XXXXXX)",
        value = phoneNumber,
        onValueChange = { phoneNumber = it },
        keyboardType = KeyboardType.Phone
    )
    Spacer(modifier = Modifier.height(12.dp))
    AuthTextField(
        label = "Email",
        value = email,
        onValueChange = { email = it },
        keyboardType = KeyboardType.Email
    )
    Spacer(modifier = Modifier.height(12.dp))
    AuthTextField(
        label = "Date of Birth (YYYY-MM-DD)",
        value = birthDate,
        onValueChange = { birthDate = it }
    )
    Spacer(modifier = Modifier.height(12.dp))
    AuthTextField(
        label = "Gender (male/female/non-binary/other)",
        value = gender,
        onValueChange = { gender = it }
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

    Spacer(modifier = Modifier.height(10.dp))
    PasswordRules(password = password)

    Spacer(modifier = Modifier.height(12.dp))
    AuthTextField(
        label = "Confirm Password",
        value = confirmPassword,
        onValueChange = { confirmPassword = it },
        keyboardType = KeyboardType.Password,
        isPassword = true,
        passwordVisible = confirmVisible,
        onTogglePassword = { confirmVisible = !confirmVisible }
    )

    if (confirmPassword.isNotEmpty() && confirmPassword != password) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Passwords do not match",
            color = Color(0xFFFF8A8A),
            style = MaterialTheme.typography.bodySmall
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            if (confirmPassword != password) {
                feedback = "Passwords do not match"
                return@Button
            }
            if (
                name.isBlank() ||
                preferredUsername.isBlank() ||
                phoneNumber.isBlank() ||
                email.isBlank() ||
                birthDate.isBlank() ||
                gender.isBlank()
            ) {
                feedback = "All fields are required."
                return@Button
            }
            if (!phoneRegex.matches(phoneNumber)) {
                feedback = "Phone must be in E.164 format, e.g. +919812345678"
                return@Button
            }
            if (!dobRegex.matches(birthDate)) {
                feedback = "DOB must be YYYY-MM-DD, e.g. 2000-01-31"
                return@Button
            }
            if (gender.lowercase() !in allowedGenders) {
                feedback = "Gender must be one of: male, female, non-binary, other"
                return@Button
            }
            if (!isPasswordPolicyValid(password)) {
                feedback = "Password must include upper, lower, number, special, and 8+ chars."
                return@Button
            }

            scope.launch {
                val result = authService.signUp(
                    SignUpInput(
                        name = name,
                        preferredUsername = preferredUsername,
                        phoneNumber = phoneNumber,
                        email = email,
                        birthDate = birthDate,
                        gender = gender.lowercase(),
                        password = password
                    )
                )
                needsConfirmation = result.requiresConfirmation
                feedback = result.message
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = AppPalette.accentOrange)
    ) {
        Text(text = "Start Your Journey", color = Color.White)
    }

    if (needsConfirmation) {
        Spacer(modifier = Modifier.height(14.dp))
        AuthTextField(
            label = "Verification Code",
            value = confirmationCode,
            onValueChange = { confirmationCode = it }
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    val response = authService.confirmSignUp(preferredUsername, confirmationCode)
                    feedback = response.message
                    if (response.success) {
                        onVerified()
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = AppPalette.accentGreen)
        ) {
            Text(text = "Verify Account", color = Color(0xFF042C23))
        }
    }

    feedback?.let {
        Spacer(modifier = Modifier.height(10.dp))
        Text(it, color = Color(0xFFCFEFED), style = MaterialTheme.typography.bodySmall)
    }

    Spacer(modifier = Modifier.height(16.dp))
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onLoginClick
    ) {
        Text("Already a warrior? Login Here", color = AppPalette.accentGreen)
    }
}
