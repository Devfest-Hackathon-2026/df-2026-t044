package com.example.yobareta.audio

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class GateMetrics(
    val currentDbFs: Float,
    val ambientBaselineDbFs: Float,
    val speechProbability: Float,
    val chimeProbability: Float,
    val isCapturingSegment: Boolean,
    val usingYamnetModel: Boolean,
    val percentAudioSent: Float
)

/**
 * On-device audio gate combining MediaPipe YAMNet AudioClassifier + adaptive ambient RMS baseline.
 *
 * Cuts a 2.0–8.0 second segment (including a 1.0 second pre-roll from [AudioRingBuffer])
 * only when speech or a PA chime exceeds the ambient loudness baseline.
 * Short transient noises (coughs, footsteps, door slams) shorter than 2.0 s are discarded locally.
 */
class OnDeviceAudioGate(
    context: Context,
    private val sampleRateHz: Int = 16000,
    private val preRollSeconds: Float = 1.0f,
    private val minSegmentSeconds: Float = 2.0f,
    private val maxSegmentSeconds: Float = 8.0f,
    private val trailingSilenceToCutSeconds: Float = 0.75f,
    private val triggerDbMarginAboveAmbient: Float = 7.5f
) {
    companion object {
        private const val TAG = "OnDeviceAudioGate"
        private const val YAMNET_ASSET_NAME = "yamnet.tflite"
        // YAMNet native window is 0.975s = 15,600 samples at 16 kHz
        private const val YAMNET_WINDOW_SAMPLES = 15600

        private val SPEECH_LABELS = setOf(
            "Speech",
            "Narration, monologue",
            "Conversation",
            "Female speech, woman speaking",
            "Male speech, man speaking",
            "Child speech, kid speaking",
            "Synthesizer",
            "Singing"
        )
        private val CHIME_LABELS = setOf(
            "Chime",
            "Bell",
            "Ding-dong",
            "Tubular bells",
            "Glockenspiel",
            "Church bell",
            "Tuning fork",
            "Sine wave",
            "Beep, bleep",
            "Alarm"
        )
    }

    private var yamnetClassifier: AudioClassifier? = null
    private val rollingClassifierBuffer = ShortArray(YAMNET_WINDOW_SAMPLES)
    private var rollingFilled = 0

    // Adaptive ambient noise floor in dBFS (initialized to typical quiet room -48 dBFS)
    private var ambientBaselineDb: Float = -48.0f
    private var baselineInitialized = false

    // Segment accumulation state
    private var isCapturing = false
    private val maxSegmentSamples = (maxSegmentSeconds * sampleRateHz).toInt()
    private val minSegmentSamples = (minSegmentSeconds * sampleRateHz).toInt()
    private val trailingSilenceLimitSamples = (trailingSilenceToCutSeconds * sampleRateHz).toInt()
    private val minActiveVoicedSamplesRequired = (0.45f * sampleRateHz).toInt()

    private var segmentBuffer = ShortArray(maxSegmentSamples)
    private var segmentLength = 0
    private var silenceSamplesSinceLastActive = 0
    private var activeVoicedSamplesInSegment = 0

    // Telemetry for "Only ~2-5% of audio leaves the phone"
    private var totalProcessedSamples: Long = 0L
    private var totalUploadedSamples: Long = 0L

    init {
        try {
            val assetList = context.assets.list("") ?: emptyArray()
            if (assetList.contains(YAMNET_ASSET_NAME)) {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(YAMNET_ASSET_NAME)
                    .build()
                val options = AudioClassifier.AudioClassifierOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.AUDIO_CLIPS)
                    .setMaxResults(8)
                    .build()
                yamnetClassifier = AudioClassifier.createFromOptions(context, options)
                Log.i(TAG, "MediaPipe YAMNet AudioClassifier initialized from assets/$YAMNET_ASSET_NAME")
            } else {
                Log.i(TAG, "assets/$YAMNET_ASSET_NAME not bundled yet; using DSP voice/chime + ambient RMS gate")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "YAMNet initialization fallback to DSP gate: ${e.message}")
            yamnetClassifier = null
        }
    }

    /**
     * Processes a chunk of 16 kHz mono PCM samples (typically 1600–3200 samples = 100–200 ms).
     *
     * @param chunk Incoming PCM samples (already appended to [ringBuffer]).
     * @param count Number of valid samples in [chunk].
     * @param ringBuffer The 15 s in-RAM ring buffer used to grab the 1.0 s pre-roll.
     * @param onSegmentCut Callback invoked when a valid 2–8 s speech/chime segment is ready.
     */
    fun processChunk(
        chunk: ShortArray,
        count: Int,
        ringBuffer: AudioRingBuffer,
        onSegmentCut: (ShortArray) -> Unit
    ): GateMetrics {
        totalProcessedSamples += count
        updateRollingClassifierWindow(chunk, count)

        val rmsDb = computeRmsDbFs(chunk, count)
        if (!baselineInitialized) {
            ambientBaselineDb = rmsDb.coerceIn(-65f, -32f)
            baselineInitialized = true
        }

        val (speechProb, chimeProb) = evaluateSpeechAndChimeProbabilities(chunk, count, rmsDb)
        val combinedProb = max(speechProb, chimeProb)
        val isAboveLoudnessGate = rmsDb >= (ambientBaselineDb + triggerDbMarginAboveAmbient) && rmsDb > -52f
        val isFrameActive = isAboveLoudnessGate && (speechProb >= 0.25f || chimeProb >= 0.22f)

        // Update ambient baseline slowly only when NOT capturing and frame is quiet/non-speech
        if (!isCapturing && !isFrameActive) {
            val alpha = if (rmsDb < ambientBaselineDb) 0.08f else 0.02f
            ambientBaselineDb = ((1f - alpha) * ambientBaselineDb + alpha * rmsDb).coerceIn(-68f, -28f)
        }

        if (!isCapturing) {
            if (isFrameActive) {
                // Open gate and seed segment with 1.0 s pre-roll from the RAM ring buffer
                isCapturing = true
                silenceSamplesSinceLastActive = 0
                activeVoicedSamplesInSegment = count
                val preRoll = ringBuffer.readLastSeconds(preRollSeconds)
                val copyLen = min(preRoll.size, maxSegmentSamples)
                System.arraycopy(preRoll, 0, segmentBuffer, 0, copyLen)
                segmentLength = copyLen
            }
        } else {
            // Currently capturing a segment: append new samples
            val spaceLeft = maxSegmentSamples - segmentLength
            val toCopy = min(count, spaceLeft)
            if (toCopy > 0) {
                System.arraycopy(chunk, 0, segmentBuffer, segmentLength, toCopy)
                segmentLength += toCopy
            }

            if (isFrameActive || (rmsDb >= ambientBaselineDb + 4.5f && combinedProb >= 0.18f)) {
                silenceSamplesSinceLastActive = 0
                activeVoicedSamplesInSegment += count
            } else {
                silenceSamplesSinceLastActive += count
            }

            val hitMaxDuration = segmentLength >= maxSegmentSamples
            val hitTrailingSilence = silenceSamplesSinceLastActive >= trailingSilenceLimitSamples

            if (hitMaxDuration || hitTrailingSilence) {
                // Trim excess trailing silence (keep ~0.25s tail for natural word endings)
                val trimSamples = max(0, silenceSamplesSinceLastActive - (sampleRateHz / 4))
                val finalLen = (segmentLength - trimSamples).coerceAtLeast(0)

                if (finalLen >= minSegmentSamples && activeVoicedSamplesInSegment >= minActiveVoicedSamplesRequired) {
                    val cutSegment = segmentBuffer.copyOfRange(0, finalLen)
                    resetCaptureState()
                    onSegmentCut(cutSegment)
                } else {
                    // Discard short transient noise (< 2s or insufficient voiced frames)
                    resetCaptureState()
                }
            }
        }

        val pctSent = if (totalProcessedSamples > 0) {
            ((totalUploadedSamples.toDouble() / totalProcessedSamples.toDouble()) * 100.0).toFloat()
        } else {
            0f
        }

        return GateMetrics(
            currentDbFs = rmsDb,
            ambientBaselineDbFs = ambientBaselineDb,
            speechProbability = speechProb,
            chimeProbability = chimeProb,
            isCapturingSegment = isCapturing,
            usingYamnetModel = (yamnetClassifier != null),
            percentAudioSent = pctSent.coerceIn(0f, 100f)
        )
    }

    fun resetSessionCounters() {
        totalProcessedSamples = 0L
        totalUploadedSamples = 0L
        resetCaptureState()
    }

    fun recordUploadedSamples(sampleCount: Int) {
        if (sampleCount > 0) {
            totalUploadedSamples += sampleCount.toLong()
        }
    }

    private fun resetCaptureState() {
        isCapturing = false
        segmentLength = 0
        silenceSamplesSinceLastActive = 0
        activeVoicedSamplesInSegment = 0
    }

    private fun updateRollingClassifierWindow(chunk: ShortArray, count: Int) {
        val n = min(count, YAMNET_WINDOW_SAMPLES)
        if (rollingFilled + n <= YAMNET_WINDOW_SAMPLES) {
            System.arraycopy(chunk, 0, rollingClassifierBuffer, rollingFilled, n)
            rollingFilled += n
        } else {
            val shift = (rollingFilled + n) - YAMNET_WINDOW_SAMPLES
            System.arraycopy(
                rollingClassifierBuffer,
                shift,
                rollingClassifierBuffer,
                0,
                YAMNET_WINDOW_SAMPLES - n
            )
            System.arraycopy(chunk, count - n, rollingClassifierBuffer, YAMNET_WINDOW_SAMPLES - n, n)
            rollingFilled = YAMNET_WINDOW_SAMPLES
        }
    }

    private fun evaluateSpeechAndChimeProbabilities(
        chunk: ShortArray,
        count: Int,
        rmsDb: Float
    ): Pair<Float, Float> {
        val classifier = yamnetClassifier
        if (classifier != null && rollingFilled >= sampleRateHz / 2) {
            try {
                val audioData = AudioData.create(
                    AudioData.AudioDataFormat.builder()
                        .setNumOfChannels(1)
                        .setSampleRate(sampleRateHz.toFloat())
                        .build(),
                    rollingFilled
                )
                val windowSlice = if (rollingFilled == YAMNET_WINDOW_SAMPLES) {
                    rollingClassifierBuffer
                } else {
                    rollingClassifierBuffer.copyOfRange(0, rollingFilled)
                }
                audioData.load(windowSlice)
                val result = classifier.classify(audioData)
                var maxSpeech = 0f
                var maxChime = 0f
                for (classification in result.classificationResults()) {
                    for (entry in classification.classifications()) {
                        for (cat in entry.categories()) {
                            val name = cat.categoryName() ?: continue
                            val score = cat.score()
                            if (name in SPEECH_LABELS || name.contains("speech", ignoreCase = true)) {
                                maxSpeech = max(maxSpeech, score)
                            }
                            if (name in CHIME_LABELS || name.contains("chime", ignoreCase = true) || name.contains("bell", ignoreCase = true)) {
                                maxChime = max(maxChime, score)
                            }
                        }
                    }
                }
                return Pair(maxSpeech, maxChime)
            } catch (e: Exception) {
                Log.w(TAG, "YAMNet classify error, falling back to DSP estimator: ${e.message}")
            }
        }

        // Fast zero-dependency DSP estimator for speech (150-3200 Hz ZCR + envelope) & PA chimes
        if (count <= 1) return Pair(0f, 0f)
        var zeroCrossings = 0
        var highFreqDiffSum = 0.0
        var energySum = 0.0
        for (i in 1 until count) {
            val prev = chunk[i - 1].toInt()
            val curr = chunk[i].toInt()
            if ((prev >= 0 && curr < 0) || (prev < 0 && curr >= 0)) {
                zeroCrossings++
            }
            val diff = (curr - prev).toDouble()
            highFreqDiffSum += diff * diff
            energySum += curr.toDouble() * curr.toDouble()
        }
        val zcrHz = (zeroCrossings.toFloat() * sampleRateHz) / (2f * count)
        val snrDb = (rmsDb - ambientBaselineDb).coerceAtLeast(0f)
        val snrFactor = (snrDb / 18f).coerceIn(0f, 1f)

        // Human speech typically has ZCR between 120 Hz and 2200 Hz
        val speechBandWeight = when {
            zcrHz in 120f..2200f -> 0.85f
            zcrHz in 80f..3200f -> 0.55f
            else -> 0.15f
        }
        // Hospital PA chimes typically have pure tonal ZCR around 500 Hz - 2600 Hz
        val chimeBandWeight = if (zcrHz in 450f..2600f) 0.80f else 0.10f

        return Pair(
            (snrFactor * speechBandWeight).coerceIn(0f, 1f),
            (snrFactor * chimeBandWeight).coerceIn(0f, 1f)
        )
    }

    private fun computeRmsDbFs(samples: ShortArray, count: Int): Float {
        if (count <= 0) return -90f
        var sumSq = 0.0
        for (i in 0 until count) {
            val norm = samples[i].toDouble() / 32768.0
            sumSq += norm * norm
        }
        val rms = sqrt(sumSq / count)
        if (rms <= 1e-5) return -90f
        return (20.0 * log10(rms)).toFloat().coerceIn(-90f, 0f)
    }

    fun close() {
        try {
            yamnetClassifier?.close()
        } catch (_: Exception) {
        }
        yamnetClassifier = null
    }
}
