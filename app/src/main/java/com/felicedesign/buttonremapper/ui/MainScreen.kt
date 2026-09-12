package com.felicedesign.buttonremapper.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateListOf
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
import com.felicedesign.buttonremapper.data.PointNeed
import com.felicedesign.buttonremapper.data.Preset
import com.felicedesign.buttonremapper.data.ScreenPoint
import com.felicedesign.buttonremapper.data.SettingsStore
import com.felicedesign.buttonremapper.service.CalibrationBus
import com.felicedesign.buttonremapper.service.KeyCaptureBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SharedPreferences is not observable, so the screen works from an immutable snapshot
 * that is re-read whenever [revision] changes: after any write, and on every resume
 * (which catches permission changes made in system settings).
 */
private data class UiSnapshot(
    val presets: List<Preset>,
    val activePresetId: Int,
    val serviceEnabled: Boolean,
    val canOverlay: Boolean,
    val keyLearned: Boolean,
    val scanCode: Int,
    val keyCode: Int,
    val deviceId: Int,
    val matchDeviceId: Boolean,
    val longPressMs: Int,
    val doublePressMs: Int,
    val tapMs: Int,
    val tapLongPressMs: Int,
    val bindings: Map<Gesture, ActionSpec>,
    val cycleIndices: Map<Gesture, Int>
) {
    val secondPressBound: Boolean
        get() = listOf(Gesture.DOUBLE, Gesture.SHORT_THEN_LONG).any {
            bindings[it]?.type?.let { type -> type != ActionType.NONE } ?: false
        }

    val usesTaps: Boolean
        get() = bindings.values.any { it.type.needsPoints }

    val needsOverlay: Boolean
        get() = bindings.values.any { it.type.needsOverlayPermission }
}

private fun snapshot(context: Context, settings: SettingsStore) = UiSnapshot(
    presets = settings.presets,
    activePresetId = settings.activePresetId,
    serviceEnabled = isAccessibilityServiceEnabled(context),
    canOverlay = Settings.canDrawOverlays(context),
    keyLearned = settings.isKeyLearned,
    scanCode = settings.scanCode,
    keyCode = settings.keyCode,
    deviceId = settings.deviceId,
    matchDeviceId = settings.matchDeviceId,
    longPressMs = settings.longPressMs,
    doublePressMs = settings.doublePressMs,
    tapMs = settings.tapMs,
    tapLongPressMs = settings.tapLongPressMs,
    bindings = Gesture.entries.associateWith { settings.action(it) },
    cycleIndices = Gesture.entries.associateWith { settings.cycleIndex(it) }
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
    var calibrating by remember { mutableStateOf<Pair<Gesture, ActionType>?>(null) }
    var editingPoints by remember { mutableStateOf<Gesture?>(null) }
    var namingPreset by remember { mutableStateOf<PresetPrompt?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("ButtonRemapper") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PresetCard(
                presets = ui.presets,
                activeId = ui.activePresetId,
                onSelect = { settings.activePresetId = it; revision++ },
                onNew = { namingPreset = PresetPrompt.New },
                onDuplicate = { namingPreset = PresetPrompt.Duplicate },
                onRename = { namingPreset = PresetPrompt.Rename },
                onDelete = { settings.deletePreset(ui.activePresetId); revision++ }
            )

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
                        appLabel = spec.arg
                            ?.takeIf { spec.type.needsApp }
                            ?.let { appLabel(context, it) },
                        cycleIndex = ui.cycleIndices[gesture] ?: 0,
                        onClick = { pickerFor = gesture },
                        onResetCycle = {
                            settings.setCycleIndex(gesture, 0)
                            revision++
                        },
                        onCheckPoints = {
                            CalibrationBus.verify(
                                ScreenPoint.decodeList(spec.arg),
                                longPress = spec.type == ActionType.LONG_PRESS_POINT
                            )
                        },
                        onEditPoints = { editingPoints = gesture }
                    )
                }
            }

            if (!ui.secondPressBound) {
                Text(
                    "Nothing is bound to a double press or a press-then-hold, so single presses " +
                        "fire the moment you release the key instead of waiting out the " +
                        "double-press window.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (ui.usesTaps) {
                Text(
                    "Tap actions fire at a fixed screen coordinate and cannot tell what is in " +
                        "front of you — knowing that would need screen-content access, which " +
                        "this app refuses. Rebind them when you are done.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
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
                    if (ui.usesTaps) {
                        SliderRow(
                            label = "Synthesised tap",
                            valueMs = ui.tapMs,
                            range = 20f..300f,
                            onChange = { settings.tapMs = it; revision++ }
                        )
                        SliderRow(
                            label = "Synthesised long-press",
                            valueMs = ui.tapLongPressMs,
                            range = 300f..3000f,
                            onChange = { settings.tapLongPressMs = it; revision++ }
                        )
                    }
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
                when {
                    type.needsApp -> appPickerFor = gesture
                    type.needsPoints -> calibrating = gesture to type
                    else -> {
                        settings.setAction(gesture, ActionSpec(type))
                        revision++
                    }
                }
            },
            onDismiss = { pickerFor = null }
        )
    }

    calibrating?.let { (gesture, type) ->
        CalibrateDialog(
            type = type,
            serviceEnabled = ui.serviceEnabled,
            onPicked = { points ->
                settings.setAction(gesture, ActionSpec(type, ScreenPoint.encodeList(points)))
                calibrating = null
                revision++
            },
            onDismiss = { calibrating = null }
        )
    }

    namingPreset?.let { prompt ->
        val active = ui.presets.firstOrNull { it.id == ui.activePresetId }
        PresetNameDialog(
            prompt = prompt,
            initial = when (prompt) {
                PresetPrompt.Rename -> active?.name.orEmpty()
                PresetPrompt.Duplicate -> "${active?.name.orEmpty()} copy"
                PresetPrompt.New -> ""
            },
            onConfirm = { name ->
                when (prompt) {
                    PresetPrompt.New ->
                        settings.activePresetId = settings.createPreset(name)

                    PresetPrompt.Duplicate ->
                        settings.activePresetId =
                            settings.createPreset(name, copyFrom = ui.activePresetId)

                    PresetPrompt.Rename -> settings.renamePreset(ui.activePresetId, name)
                }
                namingPreset = null
                revision++
            },
            onDismiss = { namingPreset = null }
        )
    }

    editingPoints?.let { gesture ->
        val spec = ui.bindings[gesture] ?: ActionSpec()
        PointEditorDialog(
            spec = spec,
            defaultMs = if (spec.type == ActionType.LONG_PRESS_POINT) {
                ui.tapLongPressMs
            } else {
                ui.tapMs
            },
            onChange = { updated ->
                settings.setActionArg(gesture, ScreenPoint.encodeList(updated))
                revision++
            },
            onDismiss = { editingPoints = null }
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

/** Which question the name dialog is asking. */
private enum class PresetPrompt(val title: String, val confirm: String) {
    New("New preset", "Create"),
    Duplicate("Duplicate preset", "Duplicate"),
    Rename("Rename preset", "Rename")
}

/**
 * Preset switching, kept at the top because it changes the meaning of everything below
 * it.
 *
 * Switching writes a single int. Every read in [SettingsStore] resolves the active
 * preset first, so the accessibility service picks up the new mapping on the very next
 * press with nothing to notify or restart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetCard(
    presets: List<Preset>,
    activeId: Int,
    onSelect: (Int) -> Unit,
    onNew: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Preset", style = MaterialTheme.typography.titleMedium)

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = preset.id == activeId,
                        onClick = { onSelect(preset.id) },
                        label = { Text(preset.name) }
                    )
                }
            }

            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onNew) { Text("New") }
                TextButton(onClick = onDuplicate) { Text("Duplicate") }
                TextButton(onClick = onRename) { Text("Rename") }
                if (presets.size > 1) {
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
            }

            Text(
                "Bindings, calibrated points and timings all belong to the preset. " +
                    "The learned key does not — that is the hardware, and it is shared.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PresetNameDialog(
    prompt: PresetPrompt,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
                if (prompt == PresetPrompt.Duplicate) {
                    Text(
                        "Copies every binding, point and timing from the current preset, " +
                            "so a variant does not mean re-aiming everything.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(prompt.confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
    cycleIndex: Int,
    onClick: () -> Unit,
    onResetCycle: () -> Unit,
    onCheckPoints: () -> Unit,
    onEditPoints: () -> Unit
) {
    val points = if (spec.type.needsPoints) ScreenPoint.decodeList(spec.arg) else emptyList()

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(gesture.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    spec.type.needsApp -> appLabel ?: spec.arg ?: spec.type.label
                    spec.type.needsPoints && points.isEmpty() ->
                        "${spec.type.label} — not calibrated"

                    else -> spec.type.label
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (points.isNotEmpty()) {
            // Marking the next step matters: the cycle counts rather than looks, so this
            // is the only way to see that it has drifted out of phase.
            Text(
                points.mapIndexed { index, point ->
                    val marker = if (points.size > 1 && index == cycleIndex) "▸" else " "
                    val hold = point.durationMs?.let { " ${it}ms" } ?: ""
                    "$marker ${index + 1}. $point$hold"
                }.joinToString("   "),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(top = 4.dp)
            )
            Row {
                TextButton(onClick = onEditPoints) { Text("Tune") }
                TextButton(onClick = onCheckPoints) { Text("Check") }
                if (points.size > 1) {
                    TextButton(onClick = onResetCycle) { Text("Reset to step 1") }
                }
            }
        }
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

/**
 * Hands the crosshair to the accessibility service and collects one or more points.
 *
 * The dialog deliberately stays composed while you leave the app - Compose keeps the
 * composition alive across a stop, so the callback still lands when you come back from
 * the camera. Settings are written from that callback rather than from a resume, so the
 * binding is saved even if this activity never regains focus.
 *
 * Re-arming on [step] is what makes a multi-point cycle work: each saved point bumps the
 * step, which disposes the old overlay and shows a fresh crosshair with a new prompt.
 */
@Composable
private fun CalibrateDialog(
    type: ActionType,
    serviceEnabled: Boolean,
    onPicked: (List<ScreenPoint>) -> Unit,
    onDismiss: () -> Unit
) {
    val collected = remember { mutableStateListOf<ScreenPoint>() }
    var step by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }

    val many = type.points == PointNeed.MANY

    DisposableEffect(step) {
        val prompt = if (many) {
            "Point ${step + 1}. Put the crosshair on the control you want this step to " +
                "tap, then Save + add for another, or Save + done to finish."
        } else {
            "Drag the crosshair onto the button you want this gesture to tap, then Save."
        }

        // The left button discards while nothing is saved and finishes once something
        // is - so a cycle can be ended without adding a point you did not want.
        val cancelLabel = if (collected.isEmpty()) "Cancel" else "Done"

        started = CalibrationBus.start(prompt, many, cancelLabel) { point, more ->
            when {
                point == null && collected.isEmpty() -> onDismiss()
                point == null -> onPicked(collected.toList())
                else -> {
                    collected.add(point)
                    if (more) step++ else onPicked(collected.toList())
                }
            }
        }
        onDispose { CalibrationBus.cancel() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (many) "Pick the spots" else "Pick a spot") },
        text = {
            Text(
                when {
                    !serviceEnabled ->
                        "Enable the accessibility service first — it is what hosts the " +
                            "crosshair and what will later fire the tap."

                    !started ->
                        "The accessibility service is not connected right now, so there " +
                            "is nothing to draw the crosshair. Try toggling it off and on."

                    else -> buildString {
                        append(
                            "A crosshair is floating on top of everything. Leave " +
                                "ButtonRemapper, open your camera, and aim it."
                        )
                        if (many) {
                            append(
                                "\n\nCalibrate each step from the state it will fire in — " +
                                    "aim at 3.5× while you are at 1×, then switch to 3.5× and " +
                                    "aim at 1×. A row that re-flows around the selected chip " +
                                    "is then measured in the layout it will actually meet."
                            )
                            append("\n\nSaved so far: ${collected.size}")
                        }
                        append(
                            "\n\nCalibrate in the orientation and mode you will shoot in — " +
                                "these are absolute screen coordinates."
                        )
                    }
                },
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Per-point tuning: where each point is, and how long the touch is held there.
 *
 * Hold time is per-point because it is not a cosmetic detail - it decides which gesture
 * the target believes it got. A chip row that snaps on a tap and opens a continuous
 * slider on a hold needs the shortest touch available, while a shutter button may want
 * a longer one. A single global value has to be wrong for one of them.
 */
@Composable
private fun PointEditorDialog(
    spec: ActionSpec,
    defaultMs: Int,
    onChange: (List<ScreenPoint>) -> Unit,
    onDismiss: () -> Unit
) {
    val points = remember(spec.arg) { ScreenPoint.decodeList(spec.arg) }
    var reaiming by remember { mutableStateOf<Int?>(null) }

    reaiming?.let { index ->
        DisposableEffect(index) {
            CalibrationBus.start(
                "Re-aim point ${index + 1}. Drag the crosshair onto the control, then Save."
            ) { point, _ ->
                if (point != null) {
                    // Keep the hold time already tuned for this point; only move it.
                    val kept = points[index].durationMs
                    onChange(points.toMutableList().also { it[index] = point.copy(durationMs = kept) })
                }
                reaiming = null
            }
            onDispose { CalibrationBus.cancel() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tune points") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(points.size) { index ->
                    val point = points[index]
                    Column(Modifier.padding(bottom = 16.dp)) {
                        Text(
                            "${index + 1}.  $point",
                            style = MaterialTheme.typography.bodyLarge
                                .copy(fontFamily = FontFamily.Monospace)
                        )
                        SliderRow(
                            label = "Hold",
                            valueMs = point.durationMs ?: defaultMs,
                            range = 10f..500f,
                            onChange = { ms ->
                                onChange(
                                    points.toMutableList()
                                        .also { it[index] = point.copy(durationMs = ms) }
                                )
                            }
                        )
                        TextButton(onClick = { reaiming = index }) { Text("Re-aim this point") }
                    }
                }
                item {
                    Text(
                        "If a tap opens a slider or a menu instead of selecting, the touch is " +
                            "being read as a hold — take this down. 10–30 ms is about as close " +
                            "to an instant tap as the gesture API allows.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
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
