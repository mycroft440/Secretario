package com.callguard.ai.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CallRepository {
    private const val PREFS = "callguard_history"
    private const val KEY_CALLS_ENCRYPTED = "calls_json_v3_encrypted"
    private const val KEY_CALLS_LEGACY = "calls_json_v2"
    private const val KEY_DEMO_SEEDED = "demo_seeded_v1"
    private const val KEY_ALIAS = "callguard_history_aes_v1"
    private const val MAX_HISTORY = 250
    private const val GCM_TAG_BITS = 128

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var appContext: Context? = null
    private val _calls = MutableStateFlow<List<CallRecord>>(emptyList())
    val calls: StateFlow<List<CallRecord>> = _calls.asStateFlow()

    fun initializeAsync(context: Context) {
        val applicationContext = context.applicationContext
        ioScope.launch { initialize(applicationContext) }
    }

    fun addAsync(context: Context, record: CallRecord) {
        val applicationContext = context.applicationContext
        ioScope.launch {
            initialize(applicationContext)
            add(record)
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = readStored()
        val demoWasSeeded = prefs.getBoolean(KEY_DEMO_SEEDED, false)

        _calls.value = when {
            stored.isNotEmpty() -> stored
            demoWasSeeded -> emptyList()
            else -> seed()
        }

        if (!demoWasSeeded) {
            prefs.edit { putBoolean(KEY_DEMO_SEEDED, true) }
        }

        if (prefs.contains(KEY_CALLS_LEGACY) || (!demoWasSeeded && _calls.value.isNotEmpty())) {
            if (persist()) prefs.edit { remove(KEY_CALLS_LEGACY) }
        }
    }

    @Synchronized
    fun add(record: CallRecord) {
        _calls.value = (listOf(record) + _calls.value).distinctBy { it.id }.take(MAX_HISTORY)
        persist()
    }

    @Synchronized
    fun upsert(record: CallRecord) {
        val current = _calls.value.toMutableList()
        val index = current.indexOfFirst { it.id == record.id }
        if (index >= 0) current[index] = record else current.add(0, record)
        _calls.value = current.sortedByDescending { it.timestamp }.take(MAX_HISTORY)
        persist()
    }

    @Synchronized
    fun replaceAll(records: List<CallRecord>) {
        _calls.value = records.sortedByDescending { it.timestamp }.take(MAX_HISTORY)
        persist()
    }

    /**
     * Destructive local erasure. It removes ciphertext and legacy plaintext first,
     * then deletes the history key. It never depends on encrypting an empty payload.
     */
    @Synchronized
    fun clearHistory() {
        _calls.value = emptyList()
        appContext?.let { context ->
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
                remove(KEY_CALLS_ENCRYPTED)
                remove(KEY_CALLS_LEGACY)
                putBoolean(KEY_DEMO_SEEDED, true)
            }
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { keyStore ->
                    if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
                }
            }
        }
    }

    @Synchronized
    fun resetDemo() {
        _calls.value = seed()
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit { putBoolean(KEY_DEMO_SEEDED, true) }
        persist()
    }

    private fun persist(): Boolean {
        val context = appContext ?: return false
        val raw = serialize(_calls.value)
        return runCatching {
            val encrypted = encrypt(raw)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString(KEY_CALLS_ENCRYPTED, encrypted)
            }
            true
        }.getOrDefault(false)
    }

    private fun readStored(): List<CallRecord> {
        val context = appContext ?: return emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val encrypted = prefs.getString(KEY_CALLS_ENCRYPTED, null)
        if (!encrypted.isNullOrBlank()) {
            return runCatching { parse(decrypt(encrypted)) }.getOrElse { emptyList() }
        }

        val legacy = prefs.getString(KEY_CALLS_LEGACY, null) ?: return emptyList()
        return runCatching { parse(legacy) }.getOrElse { emptyList() }
    }

    private fun serialize(records: List<CallRecord>): String {
        val array = JSONArray()
        records.forEach { call ->
            array.put(JSONObject().apply {
                put("id", call.id)
                put("phoneNumber", call.phoneNumber)
                put("label", call.label)
                put("decision", call.decision.name)
                put("category", call.category.name)
                put("transcript", call.transcript ?: JSONObject.NULL)
                put("summary", call.summary ?: JSONObject.NULL)
                put("callerName", call.callerName ?: JSONObject.NULL)
                put("confidence", call.confidence?.toDouble() ?: JSONObject.NULL)
                put("timestamp", call.timestamp)
                put("isDemo", call.isDemo)
            })
        }
        return array.toString()
    }

    private fun parse(raw: String): List<CallRecord> {
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    CallRecord(
                        id = obj.getLong("id"),
                        phoneNumber = obj.getString("phoneNumber"),
                        label = obj.getString("label"),
                        decision = enumValueOrDefault(obj.optString("decision"), CallDecision.PENDING),
                        category = enumValueOrDefault(obj.optString("category"), CallCategory.UNKNOWN),
                        transcript = obj.optNullableString("transcript"),
                        summary = obj.optNullableString("summary"),
                        callerName = obj.optNullableString("callerName"),
                        confidence = if (obj.isNull("confidence")) null else obj.optDouble("confidence").toFloat(),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        isDemo = obj.optBoolean("isDemo", false)
                    )
                )
            }
        }.sortedByDescending { it.timestamp }.take(MAX_HISTORY)
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val pieces = payload.split('.', limit = 2)
        require(pieces.size == 2) { "Histórico criptografado inválido" }
        val iv = Base64.decode(pieces[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(pieces[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
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

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun seed(): List<CallRecord> {
        val now = System.currentTimeMillis()
        return listOf(
            CallRecord(
                id = 1,
                phoneNumber = "389393939",
                label = "operadora",
                decision = CallDecision.BLOCKED,
                category = CallCategory.OPERATOR,
                summary = "Exemplo: oferta de operadora.",
                timestamp = now - 120_000L,
                isDemo = true
            ),
            CallRecord(
                id = 2,
                phoneNumber = "838383898",
                label = "novo número do Roberto",
                decision = CallDecision.ALLOWED,
                category = CallCategory.REAL_PERSON,
                transcript = "preciso falar com você troquei de número sou roberto",
                summary = "Exemplo: Roberto informou que trocou de número e precisa falar com você.",
                callerName = "Roberto",
                confidence = 0.96f,
                timestamp = now - 60_000L,
                isDemo = true
            ),
            CallRecord(
                id = 3,
                phoneNumber = "838383893",
                label = "robô/ligação muda",
                decision = CallDecision.BLOCKED,
                category = CallCategory.ROBOT_OR_SILENT,
                summary = "Exemplo: robô ou chamada sem fala.",
                timestamp = now,
                isDemo = true
            )
        )
    }
}
