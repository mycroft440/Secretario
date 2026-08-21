package com.callguard.ai.voip

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.callguard.ai.R

class VoipSipService : Service() {
    override fun onCreate() {
        super.onCreate()
        VoipAppContext.initialize(this)
        VoipPhoneAccountManager.register(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val settings = VoipSettingsStore.load(this)
        if (settings == null || !settings.enabled || !settings.isComplete) {
            stopSelf()
            return START_NOT_STICKY
        }

        val setupIntent = Intent(this, VoipSetupActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            10_001,
            setupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_callguard)
            .setContentTitle("CallGuard Universal ativo")
            .setContentText("Conta SIP conectada para receber ligações e fazer triagem local")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pending)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }

        UniversalSipRuntime.start(this, settings).onFailure {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        UniversalSipRuntime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL,
                "CallGuard Universal",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém a conta SIP disponível para chamadas encaminhadas ao CallGuard."
            }
        )
    }

    companion object {
        private const val ACTION_START = "com.callguard.ai.voip.START"
        private const val ACTION_STOP = "com.callguard.ai.voip.STOP"
        private const val SERVICE_CHANNEL = "callguard_voip_service"
        private const val SERVICE_NOTIFICATION_ID = 18_001

        fun start(context: Context) {
            val intent = Intent(context, VoipSipService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VoipSipService::class.java).setAction(ACTION_STOP))
        }
    }
}
