package com.felicedesign.buttonremapper.ui

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.felicedesign.buttonremapper.service.RemapAccessibilityService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

/**
 * Authoritative check for whether our service is enabled. [RemapAccessibilityService.isRunning]
 * only tells us about the current process, which may have just started.
 */
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, RemapAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
}
