package com.rivi.arwastewarrior

import android.content.Context
import android.util.Log
import com.amplifyframework.AmplifyException
import com.amplifyframework.auth.AuthUserAttributeKey
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.auth.options.AuthSignUpOptions
import com.amplifyframework.core.Amplify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val COGNITO_REGION = "ap-south-1"
private const val COGNITO_APP_CLIENT_ID = "71982vhsepp49kgqdg7lo46i6l"

object AndroidPlatformAuth {
    private var configured = false
    private const val TAG = "AndroidPlatformAuth"

    fun initialize(context: Context) {
        if (configured) return
        try {
            logResolvedAmplifyConfig(context)
            Amplify.addPlugin(AWSCognitoAuthPlugin())
            Amplify.configure(context)
            configured = true
            Log.i(TAG, "Amplify configured successfully")
        } catch (e: AmplifyException) {
            configured = false
            Log.e(TAG, "Amplify init failed", e)
        } catch (e: Exception) {
            configured = false
            Log.e(TAG, "Amplify init failed", e)
        }
    }

    private fun logResolvedAmplifyConfig(context: Context) {
        try {
            val rawId = context.resources.getIdentifier("amplifyconfiguration", "raw", context.packageName)
            if (rawId == 0) {
                Log.e(TAG, "amplifyconfiguration.json not found in res/raw")
                return
            }
            val json = context.resources.openRawResource(rawId).bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val defaultPool = obj
                .getJSONObject("auth")
                .getJSONObject("plugins")
                .getJSONObject("awsCognitoAuthPlugin")
                .getJSONObject("CognitoUserPool")
                .getJSONObject("Default")

            val poolId = defaultPool.optString("PoolId", "missing")
            val clientId = defaultPool.optString("AppClientId", "missing")
            val region = defaultPool.optString("Region", "missing")

            Log.i(
                TAG,
                "Using Cognito config: poolId=$poolId, appClientId=$clientId, region=$region"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read amplifyconfiguration.json", e)
        }
    }
}

private class AndroidCognitoAuthService : AuthService {
    private val tag = "AndroidCognitoAuth"

    override suspend fun signIn(usernameOrAlias: String, password: String): AuthResponse {
        Log.i(tag, "signIn called: aliasLength=${usernameOrAlias.length}, passwordLength=${password.length}")
        return try {
            val result = suspendCoroutine { continuation ->
                Amplify.Auth.signIn(
                    usernameOrAlias,
                    password,
                    { continuation.resume(it) },
                    { continuation.resumeWithException(it) }
                )
            }
            if (result.isSignedIn) {
                Log.i(tag, "signIn success: user is signed in")
                AuthResponse(true, "Login successful")
            } else {
                Log.w(tag, "signIn next step required: ${result.nextStep.signInStep}")
                AuthResponse(false, "Additional auth step required")
            }
        } catch (e: Exception) {
            Log.e(tag, "signIn failed", e)
            AuthResponse(false, e.message ?: e.toString())
        }
    }

    override suspend fun signUp(input: SignUpInput): SignUpResponse {
        Log.i(
            tag,
            "signUp called: username=${input.preferredUsername}, " +
                "namePresent=${input.name.isNotBlank()}, " +
                "emailDomain=${input.email.substringAfter('@', "invalid")}, " +
                "phonePrefix=${input.phoneNumber.take(4)}, " +
                "birthDate=${input.birthDate}, gender=${input.gender}, " +
                "passwordLength=${input.password.length}"
        )
        return signUpViaDirectCognito(input)
    }

    override suspend fun confirmSignUp(preferredUsername: String, code: String): AuthResponse {
        Log.i(tag, "confirmSignUp called: username=$preferredUsername, codeLength=${code.length}")
        return try {
            val result = suspendCoroutine { continuation ->
                Amplify.Auth.confirmSignUp(
                    preferredUsername,
                    code,
                    { continuation.resume(it) },
                    { continuation.resumeWithException(it) }
                )
            }
            if (result.isSignUpComplete) {
                Log.i(tag, "confirmSignUp success: account confirmed")
                AuthResponse(true, "Account verified. You can now log in.")
            } else {
                Log.w(tag, "confirmSignUp incomplete")
                AuthResponse(false, "Verification not complete yet")
            }
        } catch (e: Exception) {
            Log.e(tag, "confirmSignUp failed", e)
            AuthResponse(false, e.message ?: e.toString())
        }
    }

    override suspend fun resetPassword(usernameOrAlias: String): AuthResponse {
        Log.i(tag, "resetPassword called: aliasLength=${usernameOrAlias.length}")
        return try {
            val result = suspendCoroutine { continuation ->
                Amplify.Auth.resetPassword(
                    usernameOrAlias,
                    { continuation.resume(it) },
                    { continuation.resumeWithException(it) }
                )
            }
            val destination = result.nextStep.codeDeliveryDetails?.destination
            Log.i(tag, "resetPassword next step: ${result.nextStep.resetPasswordStep}, destination=$destination")
            AuthResponse(
                true,
                "Password reset code sent${if (destination != null) " to $destination" else ""}"
            )
        } catch (e: Exception) {
            Log.e(tag, "resetPassword failed", e)
            AuthResponse(false, e.message ?: e.toString())
        }
    }
}

actual fun platformAuthService(): AuthService = AndroidCognitoAuthService()

private suspend fun signUpViaDirectCognito(input: SignUpInput): SignUpResponse = withContext(Dispatchers.IO) {
    val tag = "AndroidCognitoAuth"
    val endpoint = "https://cognito-idp.$COGNITO_REGION.amazonaws.com/"
    val requestJson = JSONObject()
        .put("ClientId", COGNITO_APP_CLIENT_ID)
        .put("Username", input.preferredUsername)
        .put("Password", input.password)
        .put(
            "UserAttributes",
            JSONArray()
                .put(attr("name", input.name))
                .put(attr("preferred_username", input.preferredUsername))
                .put(attr("phone_number", input.phoneNumber))
                .put(attr("email", input.email))
                .put(attr("birthdate", input.birthDate))
                .put(attr("gender", input.gender))
        )

    Log.i(tag, "directSignUp requestAttrs=name,preferred_username,phone_number,email,birthdate,gender")

    try {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-amz-json-1.1")
            setRequestProperty("X-Amz-Target", "AWSCognitoIdentityProviderService.SignUp")
        }

        OutputStreamWriter(conn.outputStream).use { it.write(requestJson.toString()) }
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } else {
            conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        }

        if (code in 200..299) {
            val json = JSONObject(body)
            val details = json.optJSONObject("CodeDeliveryDetails")
            val destination = details?.optString("Destination")
            Log.i(tag, "directSignUp success: destination=$destination")
            return@withContext SignUpResponse(
                isComplete = false,
                requiresConfirmation = true,
                message = "Verification code sent${if (!destination.isNullOrBlank()) " to $destination" else ""}"
            )
        }

        val error = body.takeIf { it.isNotBlank() } ?: "HTTP $code with empty error body"
        Log.e(tag, "directSignUp failed: http=$code body=$error")
        SignUpResponse(
            isComplete = false,
            requiresConfirmation = false,
            message = "DirectSignUpError: HTTP $code $error"
        )
    } catch (e: Exception) {
        Log.e(tag, "directSignUp exception", e)
        SignUpResponse(
            isComplete = false,
            requiresConfirmation = false,
            message = "DirectSignUpException: ${e.message ?: e.toString()}"
        )
    }
}

private fun attr(name: String, value: String): JSONObject {
    return JSONObject().put("Name", name).put("Value", value)
}
