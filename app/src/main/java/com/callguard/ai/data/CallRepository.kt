package com.callguard.ai.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CallRepository {
    private const val PREFS = "callguard_history"
    private const val KEY_CALLS = "calls_json_v2"
    private const val MAX_HISTORY = 250

    private var appContext: Context? = null
    private val _calls = MutableStateFlow<List<CallRecord>>(emptyList())
    val calls: StateFlow<List<CallRecord>> = _calls.asStateFlow()

    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val stored = readStored()
        _calls.value = if (stored.isEmpty()) seed() else stored
        if (stored.isEmpty()) persist()
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

    @Synchronized
    fun resetDemo() {
        _calls.value = seed()
        persist()
    }

    private fun persist() {
        val context = appContext ?: return
        val array = JSONArray()
        _calls.value.forEach { call ->
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
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CALLS, array.toString())
            .apply()
    }

    private fun readStored(): List<CallRecord> {
        val context = appContext ?: return emptyList()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CALLS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
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
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
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
                summary = "Ligação identificada como oferta de operadora.",
                timestamp = now - 120_000L
            ),
            CallRecord(
                id = 2,
                phoneNumber = "838383898",
                label = "novo número do Roberto",
                decision = CallDecision.ALLOWED,
                category = CallCategory.REAL_PERSON,
                transcript = "preciso falar com você troquei de número sou roberto",
                summary = "Roberto informou que trocou de número e precisa falar com você.",
                callerName = "Roberto",
                confidence = 0.96f,
                timestamp = now - 60_000L
            ),
            CallRecord(
                id = 3,
                phoneNumber = "838383893",
                label = "robô/ligação muda",
                decision = CallDecision.BLOCKED,
                category = CallCategory.ROBOT_OR_SILENT,
                summary = "Robô ou chamada sem fala detectada.",
                timestamp = now
            )
        )
    }
}
