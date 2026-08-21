package com.callguard.ai

import android.app.Application
import com.callguard.ai.data.CallRepository

class CallGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Keystore and encrypted-history I/O must not delay a cold-started
        // CallScreeningService before Android's screening deadline.
        CallRepository.initializeAsync(this)
    }
}
