package intercom.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppSettings(
    val username: String = "",
    val callUrl: String = "",
    val authUsername: String = "",
    val authPassword: String = "",
    /** Optional. Only needed when the API is not served from the call URL's host. */
    val apiUrl: String = "",
    val autoJoin: Boolean = true,
    val volume: Float = DEFAULT_VOLUME,
) {
    val isConfigured: Boolean
        get() = username.isNotBlank() && CallUrl.parse(callUrl) is CallUrlResult.Ok

    companion object {
        const val DEFAULT_VOLUME = 0.75f
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_USERNAME, settings.username)
            .putString(KEY_CALL_URL, settings.callUrl)
            .putString(KEY_AUTH_USERNAME, settings.authUsername)
            .putString(KEY_AUTH_PASSWORD, PasswordCipher.encrypt(settings.authPassword))
            .putString(KEY_API_URL, settings.apiUrl)
            .putBoolean(KEY_AUTO_JOIN, settings.autoJoin)
            .putFloat(KEY_VOLUME, settings.volume)
            .apply()
        _settings.value = settings
    }

    fun setVolume(volume: Float) {
        prefs.edit().putFloat(KEY_VOLUME, volume).apply()
        _settings.update { it.copy(volume = volume) }
    }

    private fun load() = AppSettings(
        username = prefs.getString(KEY_USERNAME, "").orEmpty(),
        callUrl = prefs.getString(KEY_CALL_URL, "").orEmpty(),
        authUsername = prefs.getString(KEY_AUTH_USERNAME, "").orEmpty(),
        authPassword = PasswordCipher.decrypt(prefs.getString(KEY_AUTH_PASSWORD, "").orEmpty()),
        apiUrl = prefs.getString(KEY_API_URL, "").orEmpty(),
        autoJoin = prefs.getBoolean(KEY_AUTO_JOIN, true),
        volume = prefs.getFloat(KEY_VOLUME, AppSettings.DEFAULT_VOLUME),
    )

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_CALL_URL = "call_url"
        const val KEY_AUTH_USERNAME = "auth_username"
        const val KEY_AUTH_PASSWORD = "auth_password"
        const val KEY_API_URL = "api_url"
        const val KEY_AUTO_JOIN = "auto_join"
        const val KEY_VOLUME = "volume"
    }
}

/** Encrypts the Basic auth password at rest with a key held in the Android Keystore. */
private object PasswordCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "intercom_settings"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    // A value that cannot be decrypted (e.g. the keystore was reset) reads as
    // "no password", so the user is simply asked for it again.
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }
}
