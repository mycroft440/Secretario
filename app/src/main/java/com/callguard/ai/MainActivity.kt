package com.callguard.ai

import android.app.role.RoleManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callguard.ai.ai.FunctionGemmaTriageEngine
import com.callguard.ai.ai.ModelInstaller
import com.callguard.ai.ai.TriageCoordinator
import com.callguard.ai.data.CallDecision
import com.callguard.ai.data.CallRecord
import com.callguard.ai.data.CallRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CallGuardApp() }
    }
}

@Composable
private fun CallGuardApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F8FA)) {
            Dashboard()
        }
    }
}

@Composable
private fun Dashboard() {
    val context = LocalContext.current
    val calls by CallRepository.calls.collectAsState()
    val scope = rememberCoroutineScope()
    val coordinator = remember { TriageCoordinator(context) }
    DisposableEffect(Unit) { onDispose { coordinator.close() } }

    var modelInstalled by remember { mutableStateOf(FunctionGemmaTriageEngine(context).isModelInstalled) }
    var modelMessage by remember { mutableStateOf<String?>(null) }
    var demoNumber by remember { mutableStateOf("11999999999") }
    var demoTranscript by remember { mutableStateOf("preciso falar com você troquei de número sou roberto") }
    var demoResult by remember { mutableStateOf<String?>(null) }
    var classifying by remember { mutableStateOf(false) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            modelMessage = "Instalando IA local…"
            scope.launch {
                val result = ModelInstaller.installFromUri(context, uri)
                modelInstalled = result.isSuccess
                modelMessage = result.fold(
                    onSuccess = { "IA instalada • %.1f MB • SHA-256 %s…".format(it.bytes / 1_048_576.0, it.sha256.take(10)) },
                    onFailure = { "Falha ao instalar IA: ${it.message}" }
                )
            }
        }
    }

    val roleManager = remember { context.getSystemService(RoleManager::class.java) }
    var screeningEnabled by remember {
        mutableStateOf(roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true)
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        screeningEnabled = roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
    }

    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val dayCalls = calls.filter {
        Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == today
    }
    val realCalls = dayCalls.filter { it.decision == CallDecision.ALLOWED && !it.transcript.isNullOrBlank() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null)
                Text("  CallGuard AI", fontWeight = FontWeight.Bold, fontSize = 26.sp)
            }
            Text(
                "Triagem local • FunctionGemma 270M",
                color = Color(0xFF667085),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        item {
            StatusCard(
                screeningEnabled = screeningEnabled,
                modelInstalled = modelInstalled,
                modelMessage = modelMessage,
                onEnable = {
                    if (roleManager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true) {
                        roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                    }
                },
                onInstallModel = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) }
            )
        }

        item {
            LimitationCard()
        }

        item { SectionHeader("Ligações do dia", dayCalls.size) }
        items(dayCalls, key = { it.id }) { call -> CallRow(call) }

        item { SectionHeader("Ligações reais", realCalls.size) }
        items(realCalls, key = { "real-${it.id}" }) { call ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(call.phoneNumber, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    call.callerName?.let {
                        Text(it, color = Color(0xFF027A48), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("disse: ${call.transcript}", color = Color(0xFF344054), lineHeight = 21.sp)
                }
            }
        }

        item {
            SectionHeader("Laboratório da IA", 1)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (modelInstalled) "Testando FunctionGemma instalado" else "Modo demonstração sem modelo instalado",
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = demoNumber,
                        onValueChange = { demoNumber = it },
                        label = { Text("Número") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = demoTranscript,
                        onValueChange = { demoTranscript = it },
                        label = { Text("O que a pessoa disse") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    Button(
                        enabled = !classifying,
                        onClick = {
                            classifying = true
                            demoResult = null
                            scope.launch {
                                runCatching {
                                    coordinator.classifyAndStore(demoNumber, demoTranscript)
                                }.onSuccess { result ->
                                    demoResult = buildString {
                                        append(result.decision.name)
                                        append(" • ")
                                        append(result.category.name)
                                        append(" • ")
                                        append((result.confidence * 100).toInt())
                                        append("%\n")
                                        append(result.summary)
                                        result.followUpQuestion?.let { append("\nPergunta: $it") }
                                    }
                                }.onFailure {
                                    demoResult = "Erro: ${it.message}"
                                }
                                classifying = false
                            }
                        }
                    ) {
                        Text(if (classifying) "Analisando…" else "Analisar ligação")
                    }
                    demoResult?.let {
                        Text(it, color = Color(0xFF344054), lineHeight = 20.sp)
                    }
                    OutlinedButton(onClick = { CallRepository.resetDemo() }) {
                        Text("Restaurar 3 exemplos")
                    }
                }
            }
        }

        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun StatusCard(
    screeningEnabled: Boolean,
    modelInstalled: Boolean,
    modelMessage: String?,
    onEnable: () -> Unit,
    onInstallModel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Proteção", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (screeningEnabled) "Triagem do Android ativada" else "Triagem do Android ainda não ativada",
                color = Color(0xFFD1D5DB)
            )
            Text(
                if (modelInstalled) "FunctionGemma local instalado" else "FunctionGemma ainda não instalado • APK permanece leve",
                color = Color(0xFFD1D5DB),
                modifier = Modifier.padding(top = 3.dp)
            )
            modelMessage?.let {
                Text(it, color = Color(0xFF9CA3AF), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onEnable, enabled = !screeningEnabled) {
                    Text(if (screeningEnabled) "Ativado" else "Ativar proteção")
                }
                OutlinedButton(onClick = onInstallModel) {
                    Text(if (modelInstalled) "Trocar IA" else "Instalar IA")
                }
            }
        }
    }
}

@Composable
private fun LimitationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Estado do MVP", fontWeight = FontWeight.Bold, color = Color(0xFF9A3412))
            Text(
                "A IA, histórico e bloqueio rápido estão separados e funcionais. A triagem silenciosa completa ainda depende de uma ponte que entregue o áudio bidirecional da chamada da operadora; a API pública de CallScreeningService não oferece esse canal.",
                color = Color(0xFF9A3412),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier.background(Color(0xFFE5E7EB), RoundedCornerShape(99.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(count.toString(), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CallRow(call: CallRecord) {
    val blocked = call.decision == CallDecision.BLOCKED
    val pending = call.decision == CallDecision.PENDING
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.background(
                    when {
                        blocked -> Color(0xFFFEE2E2)
                        pending -> Color(0xFFFFF7ED)
                        else -> Color(0xFFDCFCE7)
                    },
                    RoundedCornerShape(14.dp)
                ).padding(10.dp)
            ) {
                Icon(Icons.Default.Phone, contentDescription = null)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(call.phoneNumber, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(call.label, color = Color(0xFF667085), modifier = Modifier.padding(top = 2.dp))
            }
            Text(
                when (call.decision) {
                    CallDecision.BLOCKED -> "BLOQUEADO"
                    CallDecision.ALLOWED -> "REAL"
                    CallDecision.PENDING -> "PENDENTE"
                },
                color = when (call.decision) {
                    CallDecision.BLOCKED -> Color(0xFFB42318)
                    CallDecision.ALLOWED -> Color(0xFF027A48)
                    CallDecision.PENDING -> Color(0xFFB54708)
                },
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
    }
}
