package com.callguard.ai

import android.app.Application
import com.callguard.ai.data.CallRepository

class CallGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CallRepository.initialize(this)
    }
}
