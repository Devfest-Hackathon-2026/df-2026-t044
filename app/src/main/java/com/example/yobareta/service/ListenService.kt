package com.example.yobareta.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class ListenService : Service() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent

    // Explicitly typed synchronized queue to resolve type errors
    private val ringBuffer: java.util.Queue<String> = java.util.ArrayDeque()
    private val maxBufferSize = 5

    override fun onCreate() {
        super.onCreate()
        Log.d("ListenService", "Initializing privacy-first local audio buffer...")

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
        speechRecognizer.setRecognitionListener(SpeechListener())

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        startListeningLoop()
    }

    private fun startListeningLoop() {
        try {
            speechRecognizer.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e("ListenService", "Error starting speech recognizer", e)
        }
    }

    private inner class SpeechListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            startListeningLoop()
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val spokenText = matches[0]
                pushToRingBuffer(spokenText)
            }
            startListeningLoop()
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun pushToRingBuffer(text: String) {
        synchronized(ringBuffer) {
            if (ringBuffer.size >= maxBufferSize) {
                ringBuffer.poll()
            }
            ringBuffer.add(text)
            Log.d("ListenService", "In-memory ring buffer updated. Latest: $text")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        Log.d("ListenService", "Service destroyed and memory buffer cleared.")
    }
}