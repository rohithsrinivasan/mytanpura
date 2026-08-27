package com.riyaaz.tanpura

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.riyaaz.tanpura.ui.AppRoot
import com.riyaaz.tanpura.ui.theme.TanpuraTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val controller = (application as TanpuraApplication).controller

        setContent {
            TanpuraTheme {
                AppRoot(controller)
            }
        }

        maybeAskForNotifications()
    }

    /**
     * The playback notification is how you pause the drone from the lock screen,
     * so it is worth asking for - but the app works without it.
     */
    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
