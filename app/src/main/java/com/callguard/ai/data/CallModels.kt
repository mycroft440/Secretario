package com.callguard.ai.data

enum class CallDecision { ALLOWED, BLOCKED, PENDING }

enum class CallCategory {
    REAL_PERSON,
    OPERATOR,
    MARKETING,
    SCAM,
    ROBOT_OR_SILENT,
    DELIVERY,
    JOB,
    HEALTH,
    SERVICE,
    UNKNOWN
}

data class CallRecord(
    val id: Long,
    val phoneNumber: String,
    val label: String,
    val decision: CallDecision,
    val category: CallCategory,
    val transcript: String? = null,
    val summary: String? = null,
    val callerName: String? = null,
    val confidence: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class TriageResult(
    val decision: CallDecision,
    val category: CallCategory,
    val callerName: String?,
    val summary: String,
    val confidence: Float,
    val askAgain: Boolean = false,
    val followUpQuestion: String? = null
)
