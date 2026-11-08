package com.example.yobareta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.yobareta.auth.AuthManager
import com.example.yobareta.model.RecognizeAlertResult
import com.example.yobareta.model.UserWatchProfile
import com.example.yobareta.service.ListenService
import com.example.yobareta.service.ListenServiceUiState
import com.example.yobareta.setup.KanjiReadingProposer
import com.example.yobareta.setup.ReadingOption
import com.example.yobareta.ui.theme.YobaretaTheme
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val authManager by lazy { AuthManager() }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        micPermissionGranted.value = results[Manifest.permission.RECORD_AUDIO] == true ||
            isAudioPermissionGranted()
    }

    private val micPermissionGranted = mutableStateOf(false)
    private val authStatusText = mutableStateOf("Signing in anonymously...")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppConfig.initFromContext(applicationContext)
        AuthManager.initAppCheck(applicationContext)
        micPermissionGranted.value = isAudioPermissionGranted()

        lifecycleScope.launch {
            try {
                val uid = authManager.ensureAuthenticated()
                authStatusText.value = "Firebase Auth Active (${uid.take(8)}…)"
            } catch (e: Exception) {
                authStatusText.value = "Auth warning: ${e.localizedMessage}"
            }
        }

        setContent {
            YobaretaTheme {
                val serviceState by ListenService.uiState.collectAsState()
                val isMicGranted by micPermissionGranted
                val authStatus by authStatusText

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    YobaretaAppScreen(
                        authStatus = authStatus,
                        isMicGranted = isMicGranted,
                        serviceState = serviceState,
                        onRequestPermissions = { requestAudioAndNotificationPermissions() },
                        onStartListening = { profile ->
                            ListenService.activeProfile = profile
                            val intent = Intent(this, ListenService::class.java).apply {
                                action = ListenService.ACTION_START
                            }
                            ContextCompat.startForegroundService(this, intent)
                        },
                        onStopListening = {
                            val intent = Intent(this, ListenService::class.java).apply {
                                action = ListenService.ACTION_STOP
                            }
                            startService(intent)
                        },
                        onDismissAlert = { ListenService.dismissAlert() },
                        onReplay10s = { ListenService.replayLast10Seconds() },
                        onForceSendClip = { ListenService.triggerSimulatedSegmentUpload() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        micPermissionGranted.value = isAudioPermissionGranted()
    }

    private fun isAudioPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioAndNotificationPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionsLauncher.launch(perms.toTypedArray())
    }
}

@Composable
fun YobaretaAppScreen(
    authStatus: String,
    isMicGranted: Boolean,
    serviceState: ListenServiceUiState,
    onRequestPermissions: () -> Unit,
    onStartListening: (UserWatchProfile) -> Unit,
    onStopListening: () -> Unit,
    onDismissAlert: () -> Unit,
    onReplay10s: () -> Unit,
    onForceSendClip: () -> Unit
) {
    val scrollState = rememberScrollState()

    var kanjiName by remember { mutableStateOf("東海林 太郎") }
    var selectedFullReading by remember { mutableStateOf("ショウジ タロウ") }
    var selectedSurnameReading by remember { mutableStateOf("ショウジ") }
    var selectedGivenReading by remember { mutableStateOf("タロウ") }
    var proposedOptions by remember {
        mutableStateOf(
            listOf(
                ReadingOption("ショウジ タロウ", "ショウジ", "一般的"),
                ReadingOption("トウカイリン タロウ", "トウカイリン", "別読み"),
                ReadingOption("SHOH-jee TAH-roh", "SHOH-jee", "Phonetic")
            )
        )
    }
    var showManualReadingEdit by remember { mutableStateOf(false) }

    var ticketNumber by remember { mutableStateOf("305") }
    var cloudRunUrl by remember { mutableStateOf(AppConfig.DEFAULT_CLOUD_RUN_BASE_URL) }
    var showJudgeDrawer by remember { mutableStateOf(false) }

    fun buildCurrentProfile(): UserWatchProfile {
        // Phonetic respellings ("AH-leks SMITH") are display-only; send only real-word hints.
        val (fullHints, surnameHints) = KanjiReadingProposer.buildMatchHints(kanjiName, proposedOptions)
        val primaryFull = if (KanjiReadingProposer.isPhoneticRespelling(selectedFullReading)) {
            fullHints.firstOrNull().orEmpty()
        } else {
            selectedFullReading.trim()
        }
        val primarySurname = if (KanjiReadingProposer.isPhoneticRespelling(selectedSurnameReading)) {
            surnameHints.firstOrNull().orEmpty()
        } else {
            selectedSurnameReading.trim()
        }
        val givenHints = KanjiReadingProposer.buildGivenNameHints(kanjiName, proposedOptions)
        val primaryGiven = if (KanjiReadingProposer.isPhoneticRespelling(selectedGivenReading)) {
            givenHints.firstOrNull().orEmpty()
        } else {
            selectedGivenReading.trim().ifEmpty { givenHints.firstOrNull().orEmpty() }
        }
        return UserWatchProfile(
            kanjiName = kanjiName.trim(),
            selectedFullReading = primaryFull,
            selectedSurnameReading = primarySurname,
            selectedGivenReading = primaryGiven,
            alternateFullReadings = fullHints,
            alternateSurnameReadings = surnameHints,
            alternateGivenReadings = givenHints,
            ticketNumber = ticketNumber.trim(),
            venueMode = "auto",
            languageMode = "auto",
            cloudRunBaseUrl = cloudRunUrl.trim()
        )
    }

    // Automatically enrich readings via Gemini after a brief typing pause
    LaunchedEffect(kanjiName) {
        val trimmed = kanjiName.trim()
        if (trimmed.isEmpty()) {
            proposedOptions = emptyList()
            selectedFullReading = ""
            selectedSurnameReading = ""
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(450L)
        val enriched = KanjiReadingProposer.proposeReadings(trimmed)
        if (enriched.isNotEmpty()) {
            proposedOptions = enriched
            if (enriched.none { it.fullReading == selectedFullReading }) {
                selectedFullReading = enriched.first().fullReading
                selectedSurnameReading = enriched.first().surnameReading
                val fClean = enriched.first().fullReading.replace("　", " ").trim()
                val sClean = enriched.first().surnameReading.replace("　", " ").trim()
                selectedGivenReading = fClean.replace(sClean, "").trim()
            }
        }
    }

    LaunchedEffect(kanjiName, selectedFullReading, selectedSurnameReading, selectedGivenReading, ticketNumber, cloudRunUrl) {
        ListenService.activeProfile = buildCurrentProfile()
    }

    // Full-Screen Takeover when a full 'called' or 'recall' alert fires
    val activeAlert = serviceState.latestAlert
    if (activeAlert != null && (activeAlert.tier == "called" || activeAlert.tier == "recall")) {
        FullScreenCallAlert(
            alert = activeAlert,
            hasReplayClip = serviceState.hasReplayable10sClip,
            isReplaying = serviceState.isReplayingAudio,
            onReplay10s = onReplay10s,
            onDismiss = onDismissAlert
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Minimalist App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Yobareta",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "呼ばれた • Waiting Room Call Alert",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(
                shape = RoundedCornerShape(50),
                color = if (serviceState.isListening) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (serviceState.isListening) Color(0xFF69F0AE) else Color.Gray)
                    )
                    Text(
                        text = if (serviceState.isListening) "LISTENING" else "READY",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (serviceState.isListening) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 2. Loud Network Drop Warning Banner ("Never fail silently")
        if (!serviceState.isNetworkAvailable) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚠️ NETWORK CONNECTION LOST",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Cannot verify announcements while offline. Please reconnect Wi-Fi or mobile data.",
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // 3. Subtle Amber Banner for Tier 1 'Possible' (Surname-Only Match)
        if (activeAlert != null && activeAlert.tier == "possible") {
            PossibleSurnameBanner(
                alert = activeAlert,
                hasReplayClip = serviceState.hasReplayable10sClip,
                isReplaying = serviceState.isReplayingAudio,
                onReplay10s = onReplay10s,
                onDismiss = onDismissAlert
            )
        }

        // 4. Hero Listening Status & Action Card
        ListeningHeroCard(
            isMicGranted = isMicGranted,
            serviceState = serviceState,
            kanjiName = kanjiName,
            selectedFullReading = selectedFullReading,
            ticketNumber = ticketNumber,
            onRequestPermissions = onRequestPermissions,
            onStartListening = { onStartListening(buildCurrentProfile()) },
            onStopListening = onStopListening
        )

        // 5. Streamlined 2-Field Setup Card (Name + Ticket #)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Who should we listen for?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Name Input (automatically updates readings as you type)
                OutlinedTextField(
                    value = kanjiName,
                    onValueChange = { newName ->
                        kanjiName = newName
                        val instant = KanjiReadingProposer.proposeInstantLocalReadings(newName)
                        proposedOptions = instant
                        if (instant.isNotEmpty()) {
                            selectedFullReading = instant.first().fullReading
                            selectedSurnameReading = instant.first().surnameReading
                            val fClean = instant.first().fullReading.replace("　", " ").trim()
                            val sClean = instant.first().surnameReading.replace("　", " ").trim()
                            selectedGivenReading = fClean.replace(sClean, "").trim()
                        } else {
                            selectedFullReading = ""
                            selectedSurnameReading = ""
                            selectedGivenReading = ""
                        }
                    },
                    label = { Text("Your Name (漢字 / English)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Compact Horizontal Reading Pills
                if (proposedOptions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        proposedOptions.forEach { option ->
                            val isSelected = selectedFullReading == option.fullReading
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedFullReading = option.fullReading
                                    selectedSurnameReading = option.surnameReading
                                    val fClean = option.fullReading.replace("　", " ").trim()
                                    val sClean = option.surnameReading.replace("　", " ").trim()
                                    selectedGivenReading = fClean.replace(sClean, "").trim()
                                },
                                label = {
                                    Text(
                                        text = if (option.note.isNotEmpty()) {
                                            "${option.fullReading} (${option.note})"
                                        } else {
                                            option.fullReading
                                        },
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }

                // Optional Manual Reading Toggle (hidden by default to keep UI clean)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Active: $selectedFullReading (Surname: $selectedSurnameReading, First: $selectedGivenReading)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (showManualReadingEdit) "Done" else "Edit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { showManualReadingEdit = !showManualReadingEdit }
                            .padding(4.dp)
                    )
                }

                AnimatedVisibility(visible = showManualReadingEdit) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = selectedFullReading,
                            onValueChange = { selectedFullReading = it },
                            label = { Text("Full Reading") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = selectedSurnameReading,
                            onValueChange = { selectedSurnameReading = it },
                            label = { Text("Surname") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(0.75f)
                        )
                        OutlinedTextField(
                            value = selectedGivenReading,
                            onValueChange = { selectedGivenReading = it },
                            label = { Text("First Name") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(0.75f)
                        )
                    }
                }

                // Ticket / Order Number Input
                OutlinedTextField(
                    value = ticketNumber,
                    onValueChange = { ticketNumber = it },
                    label = { Text("Ticket / Order # (番号札 — Optional, e.g. 305)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 6. Collapsible Judge Telemetry & Developer Drawer
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showJudgeDrawer = !showJudgeDrawer }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🔍 Judge Telemetry & Test Tools",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (showJudgeDrawer) "Hide ▲" else "Show ▼",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                AnimatedVisibility(visible = showJudgeDrawer) {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Gate: %s • Mic: %.1f dBFS (Ambient: %.1f dBFS)",
                                if (serviceState.usingYamnetModel) "YAMNet + RMS" else "DSP VAD + RMS",
                                serviceState.currentDbFs,
                                serviceState.ambientBaselineDbFs
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "Audio Sent to Cloud: %.1f%% (%d clips) • %s",
                                serviceState.percentAudioSent,
                                serviceState.segmentsUploadedCount,
                                serviceState.warmStatus
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )

                        if (serviceState.isListening) {
                            OutlinedButton(
                                onClick = onForceSendClip,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Send Recent 3.5s Audio Segment Now")
                            }
                        }

                        serviceState.lastError?.let { err ->
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        OutlinedTextField(
                            value = cloudRunUrl,
                            onValueChange = { newUrl: String -> cloudRunUrl = newUrl },
                            label = { Text("Cloud Run Base URL (asia-northeast1)") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = authStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ListeningHeroCard(
    isMicGranted: Boolean,
    serviceState: ListenServiceUiState,
    kanjiName: String,
    selectedFullReading: String,
    ticketNumber: String,
    onRequestPermissions: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (serviceState.isListening) {
                Color(0xFF0D3B2E)
            } else {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val targetSummary = buildString {
                if (kanjiName.isNotBlank()) {
                    append(kanjiName.trim())
                    if (selectedFullReading.isNotBlank()) append(" ($selectedFullReading)")
                }
                if (ticketNumber.isNotBlank()) {
                    if (isNotEmpty()) append("  •  ")
                    append("#${ticketNumber.trim()}")
                }
            }.ifEmpty { "Enter your name or ticket number below" }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (serviceState.isListening) "Listening for your call…" else "Ready to listen for",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (serviceState.isListening) Color(0xFFA7F3D0) else MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = targetSummary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (serviceState.isListening) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            if (serviceState.isListening) {
                // Visual Audio Level Bar (normalized from [-65 dBFS .. -15 dBFS] to [0f .. 1f])
                val normalizedLevel = ((serviceState.currentDbFs + 65f) / 50f).coerceIn(0.04f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { normalizedLevel },
                        color = if (serviceState.isCapturingSegment) Color(0xFF69F0AE) else Color(0xFF38BDF8),
                        trackColor = Color.White.copy(alpha = 0.18f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (serviceState.isCapturingSegment) {
                                "🟢 Speech detected — checking call…"
                            } else {
                                "🔒 Filtering background noise in RAM"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "Only %.1f%% audio sent",
                                serviceState.percentAudioSent
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFA7F3D0),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (!isMicGranted) {
                Button(
                    onClick = onRequestPermissions,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text("Grant Microphone & Notification Permissions", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = if (serviceState.isListening) onStopListening else onStartListening,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (serviceState.isListening) Color(0xFFD32F2F) else Color(0xFF1565C0),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text(
                        text = if (serviceState.isListening) "■ Stop Listening" else "▶ Start Listening",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun PossibleSurnameBanner(
    alert: RecognizeAlertResult,
    hasReplayClip: Boolean,
    isReplaying: Boolean,
    onReplay10s: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE65100))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "🔔 POSSIBLE CALL — SURNAME MATCH (${alert.matchedTrigger})",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = alert.instructionPlain.ifEmpty { "Surname matched — check if this call is for you" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            if (alert.transcriptMasked.isNotEmpty()) {
                Text(
                    text = "「${alert.transcriptMasked}」",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasReplayClip) {
                    Button(
                        onClick = onReplay10s,
                        enabled = !isReplaying,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isReplaying) "🔊 Replaying…" else "🔊 Replay 10s", fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
fun FullScreenCallAlert(
    alert: RecognizeAlertResult,
    hasReplayClip: Boolean,
    isReplaying: Boolean,
    onReplay10s: () -> Unit,
    onDismiss: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fullScreenAlertFlash")
    val isRecall = alert.tier == "recall"

    val primaryColor = if (isRecall) Color(0xFFB71C1C) else Color(0xFF00695C)
    val secondaryColor = if (isRecall) Color(0xFFE65100) else Color(0xFF2E7D32)

    val animatedBg by infiniteTransition.animateColor(
        initialValue = primaryColor,
        targetValue = secondaryColor,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fullScreenBg"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedBg)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Tier Badge
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.28f),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(
                    text = if (isRecall) "🚨 URGENT RE-CALL • 再度お呼び出し" else "✅ YOU WERE CALLED • お呼び出し",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }

            // Centerpiece Destination & Instruction
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (alert.destination.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 22.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "GO TO DESTINATION",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.DarkGray,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = alert.destination,
                                color = Color.Black,
                                fontWeight = FontWeight.Black,
                                fontSize = 32.sp,
                                lineHeight = 38.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Text(
                    text = alert.instructionPlain.ifEmpty { "You were called!" },
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    textAlign = TextAlign.Center
                )

                if (alert.transcriptMasked.isNotEmpty()) {
                    Text(
                        text = "「${alert.transcriptMasked}」",
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Text(
                    text = "Matched: ${alert.matchedTrigger} • Latency: ${alert.latencyMs} ms",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            // Bottom Action Buttons (10s Replay + Dismiss)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hasReplayClip) {
                    Button(
                        onClick = onReplay10s,
                        enabled = !isReplaying,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                    ) {
                        Text(
                            text = if (isReplaying) "🔊 Replaying Last 10s Audio…" else "🔊 Replay Last 10s Audio",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.25f),
                        contentColor = Color.White
                    ),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                ) {
                    Text(
                        text = "✓ I'm On My Way (Stop Buzz)",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
