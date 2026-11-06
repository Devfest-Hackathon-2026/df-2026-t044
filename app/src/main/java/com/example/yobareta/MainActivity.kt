package com.example.yobareta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var kanjiName by remember { mutableStateOf("東海林 太郎") }
    var selectedFullReading by remember { mutableStateOf("ショウジ タロウ") }
    var selectedSurnameReading by remember { mutableStateOf("ショウジ") }
    var proposedOptions by remember {
        mutableStateOf(
            listOf(
                ReadingOption("ショウジ タロウ", "ショウジ", "一般的な読み"),
                ReadingOption("トウカイリン タロウ", "トウカイリン", "別読み"),
                ReadingOption("Shoji Taro", "Shoji", "English / Romaji")
            )
        )
    }
    var isProposingReadings by remember { mutableStateOf(false) }

    var ticketNumber by remember { mutableStateOf("305") }
    var cloudRunUrl by remember { mutableStateOf(AppConfig.DEFAULT_CLOUD_RUN_BASE_URL) }
    var showAdvancedConfig by remember { mutableStateOf(false) }

    fun buildCurrentProfile(): UserWatchProfile {
        return UserWatchProfile(
            kanjiName = kanjiName.trim(),
            selectedFullReading = selectedFullReading.trim(),
            selectedSurnameReading = selectedSurnameReading.trim(),
            alternateFullReadings = proposedOptions.map { it.fullReading },
            alternateSurnameReadings = proposedOptions.map { it.surnameReading },
            ticketNumber = ticketNumber.trim(),
            venueMode = "auto",
            languageMode = "auto",
            cloudRunBaseUrl = cloudRunUrl.trim()
        )
    }

    LaunchedEffect(kanjiName, selectedFullReading, selectedSurnameReading, ticketNumber, cloudRunUrl) {
        ListenService.activeProfile = buildCurrentProfile()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Yobareta (呼ばれた)",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Waiting Room Call Detector (JA + EN) • Phone + Watch",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = if (serviceState.isListening) "● LISTENING" else "IDLE",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (serviceState.isListening) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = if (serviceState.isListening) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }

        // 2. Loud Network Drop Warning Banner ("Never fail silently")
        if (!serviceState.isNetworkAvailable) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚠️ NETWORK CONNECTION LOST",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Yobareta cannot verify announcements while offline. Please check Wi-Fi or mobile data immediately.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // 3. Full-Screen Flash / High-Contrast Alert Banner when Possible / Called / Re-call fires
        serviceState.latestAlert?.let { alert ->
            AlertFlashBanner(
                alert = alert,
                hasReplayClip = serviceState.hasReplayable10sClip,
                isReplaying = serviceState.isReplayingAudio,
                onReplay10s = onReplay10s,
                onDismiss = onDismissAlert
            )
        }

        // 4. Primary Start / Stop Listening Action
        if (!isMicGranted) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Microphone & Notification Permissions Required",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "Yobareta processes audio in a 15-second RAM buffer on-device and sends only 2–8s speech segments.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Microphone & Alert Permissions")
                    }
                }
            }
        } else {
            Button(
                onClick = {
                    if (serviceState.isListening) {
                        onStopListening()
                    } else {
                        onStartListening(buildCurrentProfile())
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (serviceState.isListening) Color(0xFFC62828) else Color(0xFF1565C0)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (serviceState.isListening) "■ STOP LISTENING" else "▶ START LISTENING (Warms Cloud Run)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // 5. Name & Ticket Trigger Setup Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "1. Name & Ticket Setup",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Kanji or English Name + Gemini Reading Proposer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = kanjiName,
                        onValueChange = { kanjiName = it },
                        label = { Text("Name (Kanji or English / 氏名)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isProposingReadings = true
                                val suggestions = KanjiReadingProposer.proposeReadings(kanjiName)
                                if (suggestions.isNotEmpty()) {
                                    proposedOptions = suggestions
                                    selectedFullReading = suggestions.first().fullReading
                                    selectedSurnameReading = suggestions.first().surnameReading
                                }
                                isProposingReadings = false
                            }
                        },
                        enabled = !isProposingReadings && kanjiName.isNotBlank()
                    ) {
                        Text(if (isProposingReadings) "..." else "✨ Readings")
                    }
                }

                // Proposed reading chips (supports both Katakana and English readings)
                if (proposedOptions.isNotEmpty()) {
                    Text(
                        text = "Select primary reading (all variants stay active):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        proposedOptions.forEach { option ->
                            val isSelected = selectedFullReading == option.fullReading
                            OutlinedButton(
                                onClick = {
                                    selectedFullReading = option.fullReading
                                    selectedSurnameReading = option.surnameReading
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${option.fullReading} (Surname: ${option.surnameReading})",
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (option.note.isNotEmpty()) {
                                        Text(
                                            text = option.note,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = selectedFullReading,
                        onValueChange = { selectedFullReading = it },
                        label = { Text("Full Reading (Kana/EN)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = selectedSurnameReading,
                        onValueChange = { selectedSurnameReading = it },
                        label = { Text("Surname (Possible)") },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                }

                OutlinedTextField(
                    value = ticketNumber,
                    onValueChange = { ticketNumber = it },
                    label = { Text("Ticket / Order Number (番号札 e.g. 305, A-12)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 6. On-Device Gate & Privacy Telemetry Card (Live Proof for Judges)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "2. On-Device Gate & Privacy Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (serviceState.usingYamnetModel) "YAMNet + RMS" else "DSP VAD + RMS",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Text(
                    text = "15 s ring buffer in RAM only (never written to disk) • 1 s pre-roll",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Mic: %.1f dBFS (Ambient: %.1f dBFS)",
                            serviceState.currentDbFs,
                            serviceState.ambientBaselineDbFs
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (serviceState.isCapturingSegment) "🟢 CUTTING 2–8s CLIP" else "⚪ GATE CLOSED",
                        fontWeight = FontWeight.Bold,
                        color = if (serviceState.isCapturingSegment) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Audio Sent to Cloud: %.1f%% (%d clips)",
                            serviceState.percentAudioSent,
                            serviceState.segmentsUploadedCount
                        ),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = serviceState.warmStatus,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (serviceState.isListening) {
                    OutlinedButton(
                        onClick = onForceSendClip,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Send Recent 3.5s Audio Segment Now (Manual Test)")
                    }
                }

                serviceState.lastError?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                TextButton(
                    onClick = { showAdvancedConfig = !showAdvancedConfig }
                ) {
                    Text(if (showAdvancedConfig) "Hide Cloud Run Endpoint Config" else "Configure Cloud Run URL & Auth Info")
                }

                if (showAdvancedConfig) {
                    OutlinedTextField(
                        value = cloudRunUrl,
                        onValueChange = { newUrl: String -> cloudRunUrl = newUrl },
                        label = { Text("Cloud Run Base URL (asia-northeast1)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = authStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun AlertFlashBanner(
    alert: RecognizeAlertResult,
    hasReplayClip: Boolean,
    isReplaying: Boolean,
    onReplay10s: () -> Unit,
    onDismiss: () -> Unit
) {
    val isFullAlert = alert.tier == "called" || alert.tier == "recall"
    val infiniteTransition = rememberInfiniteTransition(label = "alertFlash")

    val primaryColor = when (alert.tier) {
        "recall" -> Color(0xFFB71C1C)   // Urgent Crimson
        "called" -> Color(0xFF00695C)   // High-Contrast Emerald
        else -> Color(0xFFE65100)       // Amber for Possible (Surname-only)
    }
    val secondaryColor = when (alert.tier) {
        "recall" -> Color(0xFFFF8F00)
        "called" -> Color(0xFF2E7D32)
        else -> Color(0xFFF57C00)
    }

    val animatedBg by infiniteTransition.animateColor(
        initialValue = primaryColor,
        targetValue = if (isFullAlert) secondaryColor else primaryColor,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgFlash"
    )

    val tierLabel = when (alert.tier) {
        "recall" -> "🚨 URGENT RE-CALL (再度お呼び出し)"
        "called" -> "✅ YOU WERE CALLED (お呼び出し)"
        else -> "🔔 POSSIBLE CALL — SURNAME ONLY (姓のみ一致)"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(animatedBg, shape = RoundedCornerShape(16.dp))
            .border(3.dp, Color.White, shape = RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = tierLabel,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp
            )

            Text(
                text = alert.instructionPlain.ifEmpty {
                    if (alert.destination.isNotEmpty()) {
                        "You were called → ${alert.destination}"
                    } else {
                        "You were called!"
                    }
                },
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 26.sp,
                lineHeight = 32.sp
            )

            if (alert.destination.isNotEmpty()) {
                Text(
                    text = "Destination: ${alert.destination}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }

            if (alert.transcriptMasked.isNotEmpty()) {
                Text(
                    text = "Announcement: 「${alert.transcriptMasked}」",
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text(
                text = "Matched: ${alert.matchedTrigger} • Score: ${String.format(Locale.US, "%.2f", alert.score)} • Latency: ${alert.latencyMs} ms",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                        Text(
                            text = if (isReplaying) "🔊 Replaying..." else "🔊 Replay Last 10s",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                OutlinedButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.25f),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Dismiss / Stop Buzz", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
