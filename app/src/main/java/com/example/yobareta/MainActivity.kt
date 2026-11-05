package com.example.yobareta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.yobareta.auth.AuthManager
import com.example.yobareta.service.ListenService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val authManager by lazy { AuthManager() }
    private val recordAudioRequestCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use Jetpack Compose for the UI layout
        setContent {
            // You can add your Compose UI elements here later
        }

        // 1. Test Firebase Anonymous Authentication
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uid = authManager.ensureAuthenticated()
                Log.d("AuthTest", "Successfully authenticated anonymously with UID: $uid")
            } catch (e: Exception) {
                Log.e("AuthTest", "Anonymous authentication failed", e)
            }
        }

        // 2. Check microphone permission and start ListenService
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                recordAudioRequestCode
            )
        } else {
            startListenService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == recordAudioRequestCode && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListenService()
        } else {
            Log.w("PermissionTest", "Microphone permission denied by user.")
        }
    }

    private fun startListenService() {
        val serviceIntent = Intent(this, ListenService::class.java)
        startService(serviceIntent)
        Log.d("ServiceTest", "ListenService start command sent.")
    }
}