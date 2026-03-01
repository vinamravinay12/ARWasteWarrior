package com.rivi.arwastewarrior.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rivi.arwastewarrior.ui.AppPalette

val phoneRegex = Regex("^\\+[1-9]\\d{6,14}$")
val dobRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
val allowedGenders = setOf("male", "female", "non-binary", "other")

@Composable
fun AuthTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: (() -> Unit)? = null
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFC5DAE1)
        )
        Spacer(modifier = Modifier.height(6.dp))

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            visualTransformation = if (isPassword && !passwordVisible) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            trailingIcon = if (isPassword && onTogglePassword != null) {
                {
                    TextButton(onClick = onTogglePassword) {
                        Text(if (passwordVisible) "Hide" else "Show")
                    }
                }
            } else {
                null
            }
        )
    }
}

@Composable
fun PasswordRules(password: String) {
    val hasMinLength = password.length >= 8
    val hasNumber = password.any { it.isDigit() }
    val hasUpper = password.any { it.isUpperCase() }
    val hasLower = password.any { it.isLowerCase() }
    val hasSpecial = password.any { !it.isLetterOrDigit() }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF124659)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RuleLine("At least 8 characters", hasMinLength)
            RuleLine("Contains a number", hasNumber)
            RuleLine("Contains an uppercase letter", hasUpper)
            RuleLine("Contains a lowercase letter", hasLower)
            RuleLine("Contains a special character", hasSpecial)
        }
    }
}

fun isPasswordPolicyValid(password: String): Boolean {
    return password.length >= 8 &&
        password.any { it.isDigit() } &&
        password.any { it.isUpperCase() } &&
        password.any { it.isLowerCase() } &&
        password.any { !it.isLetterOrDigit() }
}

@Composable
private fun RuleLine(text: String, isMet: Boolean) {
    Text(
        text = if (isMet) "PASS  $text" else "TODO  $text",
        color = if (isMet) AppPalette.accentGreen else Color(0xFF9EC6D0),
        style = MaterialTheme.typography.bodyMedium
    )
}
