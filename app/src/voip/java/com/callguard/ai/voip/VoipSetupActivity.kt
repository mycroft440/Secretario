package com.callguard.ai.voip

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class VoipSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VoipSetupScreen() }
    }
}

@Composable
private fun VoipSetupScreen() {
    val activity = androidx.activity.compose.LocalActivity.current as? VoipSetupActivity ?: return
    val existing = remember { VoipSettingsStore.load(activity) }
    val status by UniversalSipRuntime.status.collectAsState()
    val scope = rememberCoroutineScope()

    var domain by remember { mutableStateOf(existing?.domain.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var registrar by remember { mutableStateOf(existing?.registrarUri ?: "sip:") }
    var proxy by remember { mutableStateOf(existing?.proxyUri.orEmpty()) }
    var transport by remember { mutableStateOf(existing?.transport?.takeIf { it != SipTransport.TLS } ?: SipTransport.UDP) }
    var message by remember { mutableStateOf<String?>(null) }
    var sttProgress by remember { mutableIntStateOf(if (VoskPtModelManager.isInstalled(activity)) 100 else 0) }
    var downloadingStt by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audio = grants[Manifest.permission.RECORD_AUDIO] ?: hasPermission(activity, Manifest.permission.RECORD_AUDIO)
        val notifications = Build.VERSION.SDK_INT < 33 ||
            (grants[Manifest.permission.POST_NOTIFICATIONS]
                ?: hasPermission(activity, Manifest.permission.POST_NOTIFICATIONS))
        if (audio) {
            VoipSipService.start(activity)
            message = if (notifications) "Modo universal ativado." else
                "SIP ativado, mas permita notificações para o telefone tocar após a aprovação."
        } else {
            message = "Permita acesso ao microfone para atender ligações VoIP aprovadas."
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Modo universal Android 10+", style = MaterialTheme.typography.headlineSmall)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Como funciona", fontWeight = FontWeight.Bold)
                    Text(
                        "Seu número precisa ser encaminhado/portado para uma conta SIP ou usar um número VoIP. " +
                            "A internet transporta a ligação; Vosk, FunctionGemma e a decisão rodam no aparelho. " +
                            "Isso evita depender do áudio protegido da chamada SIM e funciona sem root."
                    )
                }
            }

            Text("SIP: ${status.detail}", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(domain, { domain = it.trim() }, label = { Text("Domínio SIP") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, { username = it.trim() }, label = { Text("Usuário / número") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Senha SIP") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(registrar, { registrar = it.trim() }, label = { Text("Registrar (ex.: sip:provedor.com)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(proxy, { proxy = it.trim() }, label = { Text("Proxy opcional") }, modifier = Modifier.fillMaxWidth())

            Text("Transporte SIP")
            Row {
                RadioButton(selected = transport == SipTransport.UDP, onClick = { transport = SipTransport.UDP })
                Text("UDP", modifier = Modifier.padding(top = 12.dp, end = 16.dp))
                RadioButton(selected = transport == SipTransport.TCP, onClick = { transport = SipTransport.TCP })
                Text("TCP", modifier = Modifier.padding(top = 12.dp))
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val settings = SipAccountSettings(
                        domain = domain,
                        username = username,
                        password = password,
                        registrarUri = registrar,
                        proxyUri = proxy.ifBlank { null },
                        transport = transport,
                        enabled = true
                    )
                    runCatching {
                        require(settings.isComplete) { "Preencha domínio, usuário, senha e registrar SIP." }
                        VoipSettingsStore.save(activity, settings)
                        VoipPhoneAccountManager.register(activity).getOrThrow()
                    }.onSuccess {
                        val permissions = buildList {
                            if (!hasPermission(activity, Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33 && !hasPermission(activity, Manifest.permission.POST_NOTIFICATIONS)) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        if (permissions.isEmpty()) {
                            VoipSipService.start(activity)
                            message = "Modo universal ativado."
                        } else {
                            permissionLauncher.launch(permissions.toTypedArray())
                        }
                    }.onFailure { message = it.message }
                }
            ) { Text("Salvar e ativar") }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    existing?.let { VoipSettingsStore.save(activity, it.copy(enabled = false)) }
                    VoipSipService.stop(activity)
                    message = "Modo universal desativado."
                }
            ) { Text("Desativar SIP") }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Reconhecimento offline PT-BR", fontWeight = FontWeight.Bold)
                    Text(
                        if (VoskPtModelManager.isInstalled(activity))
                            "Modelo Vosk PT-BR instalado."
                        else
                            "Instale o modelo pequeno PT-BR (~31 MB) uma vez. Depois o STT funciona offline."
                    )
                    if (downloadingStt) Text("Baixando STT: $sttProgress%")
                    Button(
                        enabled = !downloadingStt,
                        onClick = {
                            downloadingStt = true
                            scope.launch {
                                VoskPtModelManager.install(activity) { sttProgress = it }
                                    .onSuccess { message = "STT PT-BR instalado." }
                                    .onFailure { message = "Falha ao instalar STT: ${it.message}" }
                                downloadingStt = false
                            }
                        }
                    ) {
                        Text(if (VoskPtModelManager.isInstalled(activity)) "Reinstalar STT" else "Instalar STT offline")
                    }
                }
            }

            message?.let { Text(it) }
            Text(
                "Importante: este modo não transforma a API da operadora. A chamada precisa chegar por SIP/VoIP. " +
                    "Sem encaminhamento/portabilidade para SIP, a ligação SIM original continua seguindo as limitações do Android."
            )
        }
    }
}

private fun hasPermission(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
