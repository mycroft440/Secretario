package com.callguard.ai.telecom

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Minimal dial/in-call UI required by the privileged default-dialer flavor.
 * The public flavor never contains this Activity.
 */
class PrivilegedDialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PrivilegedDialerScreen() }
    }

    companion object {
        const val EXTRA_CALL_TOKEN = "callguard.call_token"
    }
}

@Composable
private fun PrivilegedDialerScreen() {
    val activity = androidx.compose.ui.platform.LocalContext.current as PrivilegedDialerActivity
    val roleManager = remember { activity.getSystemService(RoleManager::class.java) }
    val telecomManager = remember { activity.getSystemService(TelecomManager::class.java) }
    val calls by PrivilegedCallRegistry.calls.collectAsState()

    var number by remember {
        mutableStateOf(activity.intent?.data?.schemeSpecificPart.orEmpty())
    }
    var isDialer by remember {
        mutableStateOf(roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true)
    }

    val dialerRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isDialer = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
    }

    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && number.isNotBlank()) {
            telecomManager?.placeCall(Uri.fromParts("tel", number.trim(), null), Bundle())
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("CallGuard AI — modo OEM", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (isDialer) "Discador padrão ativado" else "Esta edição precisa ser o discador padrão"
            )

            if (!isDialer && roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        dialerRoleLauncher.launch(
                            roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                        )
                    }
                ) { Text("Tornar CallGuard o telefone padrão") }
            }

            OutlinedTextField(
                value = number,
                onValueChange = { number = it.filter { char -> char.isDigit() || char == '+' } },
                label = { Text("Número") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = number.isNotBlank() && isDialer,
                onClick = {
                    if (
                        ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        telecomManager?.placeCall(
                            Uri.fromParts("tel", number.trim(), null),
                            Bundle()
                        )
                    } else {
                        phonePermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                    }
                }
            ) { Text("Ligar") }

            calls.forEach { snapshot ->
                CallCard(snapshot)
            }
        }
    }
}

@Composable
private fun CallCard(snapshot: PrivilegedCallRegistry.Snapshot) {
    val call = PrivilegedCallRegistry.get(snapshot.token) ?: return
    val audioProcessing =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            snapshot.state == Call.STATE_AUDIO_PROCESSING

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(snapshot.number ?: "Número indisponível")
            Text(callStateLabel(snapshot.state))

            when {
                snapshot.state == Call.STATE_RINGING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { call.answer(VideoProfile.STATE_AUDIO_ONLY) }) {
                            Text("Atender")
                        }
                        OutlinedButton(onClick = { call.reject(false, null) }) {
                            Text("Recusar")
                        }
                    }
                }
                audioProcessing -> {
                    Text(
                        "Triagem de áudio em andamento; a chamada ainda não foi apresentada ao usuário."
                    )
                }
                snapshot.state == Call.STATE_DISCONNECTED -> Unit
                else -> OutlinedButton(onClick = call::disconnect) {
                    Text("Encerrar")
                }
            }
        }
    }
}

private fun callStateLabel(state: Int): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (state == Call.STATE_AUDIO_PROCESSING) return "IA processando áudio"
        if (state == Call.STATE_SIMULATED_RINGING) return "Apresentando ao usuário"
    }

    return when (state) {
        Call.STATE_NEW -> "Nova"
        Call.STATE_DIALING -> "Discando"
        Call.STATE_RINGING -> "Recebendo chamada"
        Call.STATE_HOLDING -> "Em espera"
        Call.STATE_ACTIVE -> "Em chamada"
        Call.STATE_DISCONNECTED -> "Encerrada"
        Call.STATE_CONNECTING -> "Conectando"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "Selecionando conta"
        else -> "Estado $state"
    }
}
