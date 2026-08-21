package com.callguard.ai.voip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class VoipIncomingCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (token.isNullOrBlank()) {
            finish()
            return
        }

        setContent {
            val calls by UniversalSipRuntime.calls.collectAsState()
            val call = calls[token]
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(28.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Ligação aprovada", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            call?.caller ?: "Número desconhecido",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        call?.summary?.takeIf { it.isNotBlank() }?.let {
                            Text(it, modifier = Modifier.padding(top = 12.dp))
                        }
                        call?.transcript?.takeIf { it.isNotBlank() }?.let {
                            Text("disse: $it", modifier = Modifier.padding(top = 8.dp))
                        }

                        Button(
                            modifier = Modifier.fillMaxWidth().padding(top = 30.dp),
                            onClick = {
                                if (VoipTelecomConnectionRegistry.answer(token)) finish()
                            }
                        ) { Text("Atender") }

                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            onClick = {
                                VoipTelecomConnectionRegistry.reject(token)
                                finish()
                            }
                        ) { Text("Recusar") }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            intent.getStringExtra(EXTRA_TOKEN)?.let { VoipIncomingCallNotifier.cancel(this, it) }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TOKEN = "callguard.voip.incoming.token"
    }
}
