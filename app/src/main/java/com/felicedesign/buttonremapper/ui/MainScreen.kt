package com.felicedesign.buttonremapper.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.felicedesign.buttonremapper.data.ActionSpec
import com.felicedesign.buttonremapper.data.ActionType
import com.felicedesign.buttonremapper.data.Gesture
import com.felicedesign.buttonremapper.data.SettingsStore
import com.felicedesign.buttonremapper.service.KeyCaptureBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SharedPreferences is not observable, so the screen works from an immutable snapshot
 * that is re-read whenever [revision] changes: after any write, and on every resume
 * (which catches permission changes made in system settings).
 */
private data class UiSnapshot(
    val serviceEnabled: Boolean,
    val canOverlay: Boolean,
    val keyLearned: Boolean,
    val scanCode: Int,
    val keyCode: Int,
    val deviceId: Int,
    val matchDeviceId: Boolean,
    val longPressMs: Int,
    val doublePressMs: Int,
    val bindings: Map<Gesture, ActionSpec>
) {
    val doublePressBound: Boolean
        get() = bindings[Gesture.DOUBLE]?.type?.let { it != ActionType.NONE } ?: false

    val needsOverlay: Boolean
        get() = bindings.values.any { it.type.needsOverlayPermission }
}

private fun snapshot(context: Context, settings: SettingsStore) = UiSnapshot(
    serviceEnabled = isAccessibilityServiceEnabled(context),
    canOverlay = Settings.canDrawOverlays(context),
    keyLearned = settings.isKeyLearned,
    scanCode = settings.scanCode,
    keyCode = settings.keyCode,
    deviceId = settings.deviceId,
    matchDeviceId = settings.matchDeviceId,
    longPressMs = settings.longPressMs,
    doublePressMs = settings.doublePressMs,
    bindings = Gesture.entries.associateWith { settings.action(it) }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }

    var revision by remember { mutableIntStateOf(0) }
    val ui = remember(revision) { snapshot(context, settings) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var learning by remember { mutableStateOf(false) }
    var pickerFor by remember { mutableStateOf<Gesture?>(null) }
    var appPickerFor by remember { mutableStateOf<Gesture?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("ButtonRemapper") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SetupCard(
                title = "Accessibility service",
                body = if (ui.serviceEnabled) {
                    "Enabled. This service reads no screen content — it only listens for your key."
                } else {
                    "Turn on ButtonRemapper under Installed apps in Accessibility settings."
                },
                ok = ui.serviceEnabled,
                actionLabel = if (ui.serviceEnabled) null else "Open settings",
                onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            )

            SetupCard(
                title = "Your key",
                body = if (ui.keyLearned) {
                    "scanCode ${ui.scanCode}   keyCode ${ui.keyCode}   device ${ui.deviceId}"
                } else {
                    "Not learned yet. Press Learn, then press the Essential Key once."
                },
                ok = ui.keyLearned,
                actionLabel = if (ui.keyLearned) "Learn again" else "Learn key",
                onAction = { learning = true },
                monospaceBody = ui.keyLearned
            )

            if (ui.needsOverlay && !ui.canOverlay) {
                SetupCard(
                    title = "Display over other apps",
                    body = "Needed only to launch apps while ButtonRemapper is in the background. " +
                        "Android has no background-launch exemption for accessibility services.",
                    ok = false,
                    actionLabel = "Grant",
                    onAction = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                )
            }

            Text(
                "Shortcuts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )

            Card {
                Gesture.entries.forEachIndexed { index, gesture ->
                    if (index > 0) HorizontalDivider()
                    val spec = ui.bindings[gesture] ?: ActionSpec()
                    BindingRow(
                        gesture = gesture,
                        spec = spec,
                        appLabel = spec.arg?.let { appLabel(context, it) },
                        onClick = { pickerFor = gesture }
                    )
                }
            }

            if (!ui.doublePressBound) {
                Text(
                    "Nothing is bound to a double press, so single presses fire the moment you " +
                        "release the key instead of waiting out the double-press window.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                "Timing",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SliderRow(
                        label = "Long press",
                        valueMs = ui.longPressMs,
                        range = 200f..1200f,
                        onChange = { settings.longPressMs = it; revision++ }
                    )
                    SliderRow(
                        label = "Double press window",
                        valueMs = ui.doublePressMs,
                        range = 150f..600f,
                        onChange = { settings.doublePressMs = it; revision++ }
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Match device id", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Stricter, but device ids can change across reboots.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = ui.matchDeviceId,
                            onCheckedChange = { settings.matchDeviceId = it; revision++ }
                        )
                    }
                }
            }

            Spacer(Modifier.padding(8.dp))
        }
    }

    if (learning) {
        LearnKeyDialog(
            serviceEnabled = ui.serviceEnabled,
            onCaptured = { key ->
                settings.learnKey(key.scanCode, key.keyCode, key.deviceId)
                learning = false
                revision++
            },
            onDismiss = { learning = false }
        )
    }

    pickerFor?.let { gesture ->
        ActionPickerDialog(
            current = ui.bindings[gesture]?.type ?: ActionType.NONE,
            onPick = { type ->
                pickerFor = null
                if (type.needsApp) {
                    appPickerFor = gesture
                } else {
                    settings.setAction(gesture, ActionSpec(type))
                    revision++
                }
            },
            onDismiss = { pickerFor = null }
        )
    }

    appPickerFor?.let { gesture ->
        AppPickerDialog(
            onPick = { packageName ->
                settings.setAction(gesture, ActionSpec(ActionType.LAUNCH_APP, packageName))
                appPickerFor = null
                revision++
            },
            onDismiss = { appPickerFor = null }
        )
    }
}

@Composable
private fun SetupCard(
    title: String,
    body: String,
    ok: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
    monospaceBody: Boolean = false
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ok) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${if (ok) "✓" else "•"}  $title", style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = if (monospaceBody) {
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyMedium
                }
            )
            if (actionLabel != null) {
                Button(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun BindingRow(
    gesture: Gesture,
    spec: ActionSpec,
    appLabel: String?,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(gesture.label, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (spec.type == ActionType.LAUNCH_APP) {
                appLabel ?: spec.arg ?: spec.type.label
            } else {
                spec.type.label
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueMs: Int,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Int) -> Unit
) {
    Column {
        Text("$label — $valueMs ms", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = valueMs.toFloat(),
            valueRange = range,
            onValueChange = { onChange(it.toInt()) }
        )
    }
}

@Composable
private fun LearnKeyDialog(
    serviceEnabled: Boolean,
    onCaptured: (KeyCaptureBus.CapturedKey) -> Unit,
    onDismiss: () -> Unit
) {
    DisposableEffect(Unit) {
        KeyCaptureBus.startLearning { onCaptured(it) }
        onDispose { KeyCaptureBus.stopLearning() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Press your key") },
        text = {
            Text(
                if (serviceEnabled) {
                    "Press the Essential Key once. Every key is swallowed while this dialog is " +
                        "open, so nothing else will react."
                } else {
                    "Enable the accessibility service first — without it no key events reach the app."
                }
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ActionPickerDialog(
    current: ActionType,
    onPick: (ActionType) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an action") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                ActionType.entries.groupBy { it.group }.forEach { (group, types) ->
                    item {
                        Text(
                            group,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(types) { type ->
                        Text(
                            type.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (type == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(type) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private data class LaunchableApp(val packageName: String, val label: String)

@Composable
private fun AppPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { info ->
                    val appInfo: ApplicationInfo = info.activityInfo.applicationInfo
                    LaunchableApp(appInfo.packageName, pm.getApplicationLabel(appInfo).toString())
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an app") },
        text = {
            val list = apps
            if (list == null) {
                Text("Loading…")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list) { app ->
                        Text(
                            app.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(app.packageName) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun appLabel(context: Context, packageName: String): String? = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
} catch (e: PackageManager.NameNotFoundException) {
    null
}
