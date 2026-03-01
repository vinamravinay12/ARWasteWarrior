package com.rivi.arwastewarrior

class UnsupportedAuthService : AuthService {
    override suspend fun signIn(usernameOrAlias: String, password: String): AuthResponse {
        return AuthResponse(false, "Auth is not configured for this platform yet.")
    }

    override suspend fun signUp(input: SignUpInput): SignUpResponse {
        return SignUpResponse(
            isComplete = false,
            requiresConfirmation = false,
            message = "Auth is not configured for this platform yet."
        )
    }

    override suspend fun confirmSignUp(preferredUsername: String, code: String): AuthResponse {
        return AuthResponse(false, "Auth is not configured for this platform yet.")
    }

    override suspend fun resetPassword(usernameOrAlias: String): AuthResponse {
        return AuthResponse(false, "Auth is not configured for this platform yet.")
    }
}
