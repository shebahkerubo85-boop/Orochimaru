package com.lagradost.cloudstream3.syncproviders

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.splitUrlParameters
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.security.SecureRandom

data class AuthLoginPage(
    /** The website to open to authenticate */
    val url: String,
    /**
     * State/control code to verify against the redirectUrl to make sure the request is valid.
     * This parameter will be saved, and then used in AuthAPI::login.
     */
    val payload: String? = null,
)

@Serializable
data class AuthToken(
    @JsonProperty("accessToken") @SerialName("accessToken")
    val accessToken: String? = null,
    @JsonProperty("refreshToken") @SerialName("refreshToken")
    val refreshToken: String? = null,
    @JsonProperty("accessTokenLifetime") @SerialName("accessTokenLifetime")
    val accessTokenLifetime: Long? = null,
    @JsonProperty("refreshTokenLifetime") @SerialName("refreshTokenLifetime")
    val refreshTokenLifetime: Long? = null,
    @JsonProperty("payload") @SerialName("payload")
    val payload: String? = null,
) {
    fun isAccessTokenExpired(marginSec: Long = 10L) =
        accessTokenLifetime != null && unixTime + marginSec >= accessTokenLifetime

    fun isRefreshTokenExpired(marginSec: Long = 10L) =
        refreshTokenLifetime != null && unixTime + marginSec >= refreshTokenLifetime
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AuthUser(
    @JsonProperty("name") @SerialName("name")
    val name: String,
    /** This id is unique for the service, and as such can be used to identify the account.
     * Note that the id is NOT unique across services.
     * This should be handled by the idPrefix. */
    @JsonProperty("id") @SerialName("id")
    val id: Int,
    @JsonProperty("profilePicture") @SerialName("profilePicture")
    val profilePicture: String? = null,
    @JsonProperty("profilePictureHeaders") @JsonAlias("profilePictureHeader")
    @SerialName("profilePictureHeaders") @JsonNames("profilePictureHeader")
    val profilePictureHeaders: Map<String, String>? = null,
)

@Serializable
data class AuthData(
    @JsonProperty("user") @SerialName("user") val user: AuthUser,
    @JsonProperty("token") @SerialName("token") val token: AuthToken,
)

data class AuthPinData(
    val deviceCode: String,
    val userCode: String,
    /** QR Code url */
    val verificationUrl: String,
    /** In seconds */
    val expiresIn: Int,
    /** Check if the code has been verified interval */
    val interval: Int,
)

/** The login field requirements to display to the user */
data class AuthLoginRequirement(
    val password: Boolean = false,
    val username: Boolean = false,
    val email: Boolean = false,
    val server: Boolean = false,
)

/** What the user responds to the AuthLoginRequirement */
@Serializable
data class AuthLoginResponse(
    @JsonProperty("password") @SerialName("password") val password: String?,
    @JsonProperty("username") @SerialName("username") val username: String?,
    @JsonProperty("email") @SerialName("email") val email: String?,
    @JsonProperty("server") @SerialName("server") val server: String?,
)

/** Stateless Authentication class used for all personalized content */
abstract class AuthAPI {
    open val name: String = "NONE"
    open val idPrefix: String = "NONE"

    /** Drawable icon of the service */
    open val icon: Int? = null

    /** If this service requires an account to use */
    open val requiresLogin: Boolean = true

    /** Link to a website for creating a new account */
    open val createAccountUrl: String? = null

    /** The sensitive redirect URL from OAuth should contain "/redirectUrlIdentifier" to trigger the login */
    open val redirectUrlIdentifier: String? = null

    /** Has OAuth2 login support, including login, loginRequest and refreshToken */
    open val hasOAuth2: Boolean = false

    /** Has on device pin support, aka login with a QR code */
    open val hasPin: Boolean = false

    /** Has in app login support, aka login with a dialog */
    open val hasInApp: Boolean = false

    /** The requirements to login in app */
    open val inAppLoginRequirement: AuthLoginRequirement? = null

    companion object {
        val unixTime: Long
            get() = APIHolder.unixTime

        val unixTimeMs: Long
            get() = unixTimeMS

        fun splitRedirectUrl(redirectUrl: String): Map<String, String> {
            return splitUrlParameters(
                redirectUrl.replace(APP_STRING, "https").replace("/#", "?")
            )
        }

        fun generateCodeVerifier(): String {
            val secureRandom = SecureRandom()
            val codeVerifierBytes = ByteArray(96)
            secureRandom.nextBytes(codeVerifierBytes)
            return base64Encode(codeVerifierBytes).trimEnd('=')
                .replace("+", "-").replace("/", "_").replace("\n", "")
        }
    }

    @Throws
    open fun isValidRedirectUrl(url: String): Boolean =
        redirectUrlIdentifier != null && url.contains("/$redirectUrlIdentifier")

    @Throws
    open suspend fun login(redirectUrl: String, payload: String?): AuthToken? =
        throw NotImplementedError()

    @Throws
    open fun loginRequest(): AuthLoginPage? = throw NotImplementedError()

    @Throws
    open suspend fun pinRequest(): AuthPinData? = throw NotImplementedError()

    @Throws
    open suspend fun refreshToken(token: AuthToken): AuthToken? = throw NotImplementedError()

    @Throws
    open suspend fun login(payload: AuthPinData): AuthToken? = throw NotImplementedError()

    @Throws
    open suspend fun login(form: AuthLoginResponse): AuthToken? = throw NotImplementedError()

    @Throws
    open suspend fun user(token: AuthToken?): AuthUser? = throw NotImplementedError()

    @Throws
    open suspend fun invalidateToken(token: AuthToken): Nothing = throw NotImplementedError()
}
