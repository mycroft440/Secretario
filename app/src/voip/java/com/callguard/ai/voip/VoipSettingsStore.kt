package com.callguard.ai.voip

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SipAccountSettings(
    val domain: String,
    val username: String,
    val password: String,
    val registrarUri: String,
    val proxyUri: String?,
    val transport: SipTransport,
    val enabled: Boolean
) {
    val isComplete: Boolean
        get() = domain.isNotBlank() && username.isNotBlank() && password.isNotBlank() &&
            registrarUri.startsWith("sip:")
}

enum class SipTransport { UDP, TCP, TLS }

object VoipSettingsStore {
    private const val PREFS = "callguard_voip_settings"
    private const val CIPHERTEXT = "encrypted_account"
    private const val KEY_ALIAS = "callguard_voip_settings_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun load(context: Context): SipAccountSettings? = runCatching {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CIPHERTEXT, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12)
        val iv = packed.copyOfRange(0, 12)
        val encrypted = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        SipAccountSettings(
            domain = json.getString("domain"),
            username = json.getString("username"),
            password = json.getString("password"),
            registrarUri = json.getString("registrarUri"),
            proxyUri = json.optString("proxyUri").takeIf { it.isNotBlank() },
            transport = runCatching { SipTransport.valueOf(json.optString("transport", "UDP")) }
                .getOrDefault(SipTransport.UDP),
            enabled = json.optBoolean("enabled", false)
        )
    }.getOrNull()

    fun save(context: Context, settings: SipAccountSettings) {
        require(settings.domain.length <= 255)
        require(settings.username.length <= 255)
        require(settings.password.length <= 512)
        require(settings.registrarUri.length <= 512)
        require((settings.proxyUri?.length ?: 0) <= 512)

        val json = JSONObject()
            .put("domain", settings.domain.trim())
            .put("username", settings.username.trim())
            .put("password", settings.password)
            .put("registrarUri", settings.registrarUri.trim())
            .put("proxyUri", settings.proxyUri?.trim().orEmpty())
            .put("transport", settings.transport.name)
            .put("enabled", settings.enabled)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json)
        val packed = cipher.iv + encrypted
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CIPHERTEXT, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
