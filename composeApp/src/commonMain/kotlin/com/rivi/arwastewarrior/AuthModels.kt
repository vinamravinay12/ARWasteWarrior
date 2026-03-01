package com.rivi.arwastewarrior

data class SignUpInput(
    val name: String,
    val preferredUsername: String,
    val phoneNumber: String,
    val email: String,
    val birthDate: String,
    val gender: String,
    val password: String
)

data class SignUpResponse(
    val isComplete: Boolean,
    val requiresConfirmation: Boolean,
    val message: String
)

data class AuthResponse(
    val success: Boolean,
    val message: String
)

interface AuthService {
    suspend fun signIn(usernameOrAlias: String, password: String): AuthResponse
    suspend fun signUp(input: SignUpInput): SignUpResponse
    suspend fun confirmSignUp(preferredUsername: String, code: String): AuthResponse
    suspend fun resetPassword(usernameOrAlias: String): AuthResponse
}

expect fun platformAuthService(): AuthService
