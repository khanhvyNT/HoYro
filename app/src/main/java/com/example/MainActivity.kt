package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                ColorDetectorApp()
            }
        }
    }
}

@Composable
fun ColorDetectorApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isRunning by DetectionState.isServiceRunning.collectAsStateWithLifecycle()
    val metrics by DetectionState.metrics.collectAsStateWithLifecycle()
    val neuralStatus by DetectionState.neuralStatus.collectAsStateWithLifecycle()
    val isAccessibilityActive by NeuralAccessibilityService.isServiceActive.collectAsStateWithLifecycle()

    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    // Function to re-check permissions from system directly
    val refreshPermissions = {
        val overlayAllowed = Settings.canDrawOverlays(context)
        hasOverlayPermission = overlayAllowed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Auto-refresh permission state whenever user returns to the app from Settings (ON_RESUME)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Launcher for Overlay Settings to immediately update state when returning
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        refreshPermissions()
    }

    val requestOverlayPermission = {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            overlayPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                overlayPermissionLauncher.launch(fallbackIntent)
            } catch (e2: Exception) {
                Toast.makeText(context, "Cannot open Settings: ${e2.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Launcher for Notification Permission (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    // Launcher for MediaProjection screen capture intent
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(context, result.resultCode, result.data!!)
            Toast.makeText(context, "Color Detection Started", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val mpManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Crosshair Color Detector",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Phase 1 - In-Memory Pixel Detection",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RunningBadge(isRunning = isRunning)
            }

            // Target Device Spec Card
            DeviceSpecsCard()

            // Algorithm & Anti-Interference Settings Card
            AlgorithmSettingsCard()

            // Live Detection HUD Card
            LiveDetectionCard(metrics = metrics, isRunning = isRunning)

            // Artificial Neural Reflex System Card
            PatientNeuralStatusCard(
                neuralStatus = neuralStatus,
                isAccessibilityActive = isAccessibilityActive
            )

            // Pre-Start Edge Trigger (x, y) & Hold Duration (ms) Configuration Card
            PreStartParametersConfigCard(isRunning = isRunning)

            // Hệ thống Macro Phản xạ Nhiều Điểm (Oppo / Xiaomi Game Turbo)
            MultiPointMacroReflexCard(
                isAccessibilityActive = isAccessibilityActive,
                isRunning = isRunning
            )

            // Permissions Checklist Card
            PermissionsCard(
                hasOverlayPermission = hasOverlayPermission,
                hasNotificationPermission = hasNotificationPermission,
                isAccessibilityActive = isAccessibilityActive,
                onRequestOverlay = requestOverlayPermission,
                onRequestNotification = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onRequestAccessibility = {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Cannot open Accessibility settings", Toast.LENGTH_SHORT).show()
                    }
                },
                onRefresh = {
                    refreshPermissions()
                    val statusText = if (hasOverlayPermission) "Overlay: GRANTED ✓" else "Overlay: NOT GRANTED"
                    Toast.makeText(context, statusText, Toast.LENGTH_SHORT).show()
                }
            )

            // Primary Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        // Always perform a live check against Android system
                        val liveOverlayAllowed = Settings.canDrawOverlays(context)
                        hasOverlayPermission = liveOverlayAllowed

                        if (!liveOverlayAllowed) {
                            Toast.makeText(
                                context,
                                "Please grant 'Display over other apps' to display floating subtitle",
                                Toast.LENGTH_LONG
                            ).show()
                            requestOverlayPermission()
                            return@Button
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }

                        mpManager?.let { manager ->
                            mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
                        }
                    },
                    enabled = !isRunning,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("start_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00C853)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("START DETECTION", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        ScreenCaptureService.stop(context)
                        OverlayService.stop(context)
                        DetectionState.reset()
                        Toast.makeText(context, "Color Detection Stopped", Toast.LENGTH_SHORT).show()
                    },
                    enabled = isRunning,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("stop_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD50000)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("STOP DETECTION", fontWeight = FontWeight.Bold)
                }
            }

            // Phase 1 Subtitle Simulation & Test Controls
            SimulationCard(
                onShowOverlay = { OverlayService.start(context) },
                onHideOverlay = { OverlayService.stop(context) },
                onSimulateStatus = { status, r, g, b, redP, greenP, innerRed, reason ->
                    val now = System.currentTimeMillis()
                    DetectionState.updateMetrics(
                        DetectionMetrics(
                            result = status,
                            redPixels = redP,
                            greenPixels = greenP,
                            otherPixels = (400 - redP - greenP).coerceAtLeast(0),
                            innerRedPixels = innerRed,
                            innerGreenPixels = if (status == DetectionResult.GREEN) 5 else 0,
                            centerR = r,
                            centerG = g,
                            centerB = b,
                            redScore = if (status == DetectionResult.RED) 45f else 0f,
                            greenScore = if (status == DetectionResult.GREEN) 45f else 0f,
                            fps = 33,
                            frameCount = 120,
                            lastUpdateTimeMs = now,
                            triggerReason = reason
                        )
                    )
                    val decision = DetectionState.neuralReflex.process(status, now)
                    if (decision == ReflexDecision.REFLEX_TAP && DetectionState.isMotorReflexEnabled) {
                        NeuralAccessibilityService.dispatchTap(
                            x = DetectionState.TAP_X,
                            y = DetectionState.TAP_Y,
                            detectionTimestamp = now,
                            onLatencyMeasured = { latencyMs ->
                                DetectionState.neuralReflex.recordLatency(latencyMs)
                            }
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun RunningBadge(isRunning: Boolean) {
    val bgColor = if (isRunning) Color(0xFF1B5E20) else Color(0xFF37474F)
    val dotColor = if (isRunning) Color(0xFF00E676) else Color(0xFF90A4AE)
    val text = if (isRunning) "ACTIVE" else "IDLE"

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
fun DeviceSpecsCard() {
    val targetX by DetectionState.targetXFlow.collectAsStateWithLifecycle()
    val targetY by DetectionState.targetYFlow.collectAsStateWithLifecycle()
    val tapX by DetectionState.tapXFlow.collectAsStateWithLifecycle()
    val tapY by DetectionState.tapYFlow.collectAsStateWithLifecycle()
    val motorX by DetectionState.peripheralMotorXFlow.collectAsStateWithLifecycle()
    val motorY by DetectionState.peripheralMotorYFlow.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Target Device",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "CẤU HÌNH THUẬT TOÁN & TÂM NGẮM",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "• Thiết bị mục tiêu: ${DetectionState.DEVICE_MODEL} (Landscape 1604 x 720 px)",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• Tọa độ tâm ngắm cảm biến (Sensor): X = $targetX, Y = $targetY",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• Tọa độ phản xạ Edge Trigger: X = ${tapX.toInt()}, Y = ${tapY.toInt()}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• Cơ quan vận động ngoại biên: X = ${motorX.toInt()}, Y = ${motorY.toInt()} (Vuốt kéo bám vi khuẩn que dọc)",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• Hậu xử lý Tap: Vuốt dịch chuyển Y + 20 ngay sau khi tap để chống tâm nháy đỏ gây loạn phản xạ",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• Nút On/Off nổi: Icon tròn độc lập di chuyển tự do khắp màn hình (chạm nhanh để bật/tắt)",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• Lõi tâm ngắm (Inner Core): 7x7 px quanh tâm (Ưu tiên số 1 - Chống cỏ/cây lấn át)",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• Vùng quét phụ (ROI): Trọng số giảm dần theo khoảng cách (5x tại tâm -> 1x tại rìa)",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• Nhận diện màu: Kiểm tra tỷ lệ R/(G,B) tương quan (chống ánh sáng đổi màu)",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "• Bộ lọc nhiễu ảnh: Chặn dải màu trắng xanh và tâm vòng ngắm cam/vàng cam",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun AlgorithmSettingsCard() {
    var centerPriority by remember { mutableStateOf(DetectionState.centerPriorityEnabled) }
    var selectedRoiRadius by remember { mutableStateOf(DetectionState.roiRadius) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "BỘ CHỐNG NHIỄU MÀU NỀN (ANTI-BACKGROUND)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Switch: Center Priority
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Ưu tiên tuyệt đối tâm ngắm (Center Priority)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Khi tâm đỏ, lập tức báo RED, không bị màu cỏ xanh/cảnh vật xung quanh lấn át",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = centerPriority,
                    onCheckedChange = { checked ->
                        centerPriority = checked
                        DetectionState.centerPriorityEnabled = checked
                    }
                )
            }

            // Presets for ROI Radius
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Kích thước vùng quét (ROI Size):",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf(
                        Pair("Chặt (6x6)", 3),
                        Pair("Tiêu chuẩn (14x14)", 7),
                        Pair("Rộng (20x20)", 10)
                    )
                    presets.forEach { (label, r) ->
                        val isSelected = selectedRoiRadius == r
                        OutlinedButton(
                            onClick = {
                                selectedRoiRadius = r
                                DetectionState.roiRadius = r
                            },
                            modifier = Modifier.weight(1f).height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = if (isSelected) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                            border = if (isSelected) {
                                androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                ButtonDefaults.outlinedButtonBorder
                            }
                        ) {
                            Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveDetectionCard(metrics: DetectionMetrics, isRunning: Boolean) {
    val targetStatusColor = when (metrics.result) {
        DetectionResult.RED -> Color(0xFFFF1744)
        DetectionResult.GREEN -> Color(0xFF00E676)
        DetectionResult.SCANNING -> Color.White
    }
    val animatedStatusColor by animateColorAsState(targetStatusColor, label = "statusColor")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F172A)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REAL-TIME DETECTION HUD",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8)
                )
                Text(
                    text = metrics.triggerReason,
                    style = MaterialTheme.typography.labelSmall,
                    color = animatedStatusColor,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Prominent Subtitle HUD Display
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E293B))
                    .border(1.5.dp, animatedStatusColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = metrics.result.subtitleText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = animatedStatusColor,
                        fontFamily = FontFamily.SansSerif
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "FLOATING OVERLAY SUBTITLE OUTPUT",
                        fontSize = 10.sp,
                        color = Color(0xFF64748B),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Real-Time Pixel & Color Breakdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Center Pixel (801, 359)",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(metrics.centerR, metrics.centerG, metrics.centerB))
                                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
                        )
                        Text(
                            text = "RGB: [${metrics.centerR}, ${metrics.centerG}, ${metrics.centerB}]",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Processing Rate",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "${metrics.fps} FPS (~30ms loop)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Inner Core vs ROI Breakdown
            Surface(
                color = Color(0xFF1E293B).copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Lõi tâm ngắm (Core 7x7):",
                            fontSize = 11.sp,
                            color = Color(0xFFE2E8F0),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "RED: ${metrics.innerRedPixels}  |  GREEN: ${metrics.innerGreenPixels}",
                            fontSize = 11.sp,
                            color = if (metrics.innerRedPixels > 0) Color(0xFFFF5252) else Color(0xFF94A3B8),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Toàn vùng (ROI):",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = "RED: ${metrics.redPixels} (Score: %.1f) | GREEN: ${metrics.greenPixels} (Score: %.1f)".format(metrics.redScore, metrics.greenScore),
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PatientNeuralStatusCard(
    neuralStatus: NeuralStatusData,
    isAccessibilityActive: Boolean
) {
    val context = LocalContext.current
    val isMotorEnabled by DetectionState.isMotorReflexEnabledFlow.collectAsStateWithLifecycle()
    val tapX by DetectionState.tapXFlow.collectAsStateWithLifecycle()
    val tapY by DetectionState.tapYFlow.collectAsStateWithLifecycle()
    val targetX by DetectionState.targetXFlow.collectAsStateWithLifecycle()
    val targetY by DetectionState.targetYFlow.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F172A)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (neuralStatus.currentState == NeuralState.STIMULATED) Color(0xFFFF1744) else Color(0xFF334155)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "PATIENT NEURAL STATUS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        text = "Artificial Neural Reflex System",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                Surface(
                    color = if (isAccessibilityActive) Color(0xFF1B5E20) else Color(0xFF451A03),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isAccessibilityActive) "MOTOR READY ✓" else "ACCESSIBILITY OFF",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAccessibilityActive) Color(0xFF4ADE80) else Color(0xFFFB923C),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Sensory Input & Motor Response Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // SENSORY INPUT
                Surface(
                    modifier = Modifier.weight(1f),
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "SENSORY INPUT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val sensorColor = when (neuralStatus.sensoryInput) {
                            DetectionResult.RED -> Color(0xFFFF1744)
                            DetectionResult.GREEN -> Color(0xFF00E676)
                            DetectionResult.SCANNING -> Color.White
                        }
                        Text(
                            text = neuralStatus.sensoryInput.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = sensorColor
                        )
                        Text(
                            text = "Sensor: ($targetX, $targetY)",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                // MOTOR RESPONSE
                Surface(
                    modifier = Modifier.weight(1f),
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "MOTOR RESPONSE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "TAP (${tapX.toInt()}, ${tapY.toInt()})",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = if (isMotorEnabled && isAccessibilityActive) Color(0xFF38BDF8) else Color(0xFF64748B)
                        )
                        Text(
                            text = "Edge-triggered (<100ms)",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }
            }

            // State Transition Row
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "STATE TRANSITION",
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${neuralStatus.previousState.name} → ${neuralStatus.currentState.name}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "REFLEX EVENT COUNT",
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${neuralStatus.reflexCount} TAPS",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFFBBF24)
                        )
                    }
                }
            }

            // Latency & Benchmark stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Reflex latency: ${if (neuralStatus.lastLatencyMs > 0) "${neuralStatus.lastLatencyMs} ms" else "Ready"}",
                    fontSize = 11.sp,
                    color = if (neuralStatus.lastLatencyMs in 1..100) Color(0xFF4ADE80) else Color(0xFFCBD5E1),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                if (!isAccessibilityActive) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot open Accessibility settings", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Bật Accessibility", fontSize = 10.sp)
                    }
                }
            }

            // Switch: Enable Motor Reflex
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Phản xạ vận động tự động (Motor Reflex Tap)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = "Chỉ tap 1 lần khi chuyển A → RED, chặn spam RED → RED",
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                Switch(
                    checked = isMotorEnabled,
                    onCheckedChange = { checked ->
                        DetectionState.isMotorReflexEnabled = checked
                    }
                )
            }

            // Real-time Sustained Hold Status (If currently verifying)
            if (neuralStatus.currentState == NeuralState.HOLDING) {
                Surface(
                    color = Color(0xFFD97706).copy(alpha = 0.25f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "⏳ ĐANG XÁC THỰC GIỮ ĐỎ:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFBBF24)
                        )
                        Text(
                            text = "${neuralStatus.holdElapsedMs}ms / ${neuralStatus.holdTargetMs}ms",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )
                    }
                }
            }

            // Noise Rejections Counter (Ruồi bay / Hạt bụi)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "BỘ LỌC CHỐNG NHIỄU RUỒI BAY & HẠT BỤI",
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Tự động hủy kích hoạt nếu đỏ biến mất trước 500-800ms",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                    Text(
                        text = "${neuralStatus.noiseRejectionCount} ĐÃ CHẶN",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF10B981)
                    )
                }
            }

            // Switch: Enable Sustained Hold Filter
            val isHoldEnabled by DetectionState.isHoldVerificationEnabledFlow.collectAsStateWithLifecycle()
            val holdDuration by DetectionState.holdConfirmationDurationMsFlow.collectAsStateWithLifecycle()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bộ lọc giữ nhắm ổn định (Hold Filter)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = "Phải giữ liên tục ở tầm đỏ 500-800ms trước khi phản xạ",
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                Switch(
                    checked = isHoldEnabled,
                    onCheckedChange = { checked ->
                        DetectionState.isHoldVerificationEnabled = checked
                    }
                )
            }

            // Hold Duration Presets: 500ms, 600ms, 700ms, 800ms
            if (isHoldEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Thời gian giữ xác nhận tối thiểu:",
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1)
                        )
                        Text(
                            text = "$holdDuration ms",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(500L, 600L, 700L, 800L).forEach { ms ->
                            val isSelected = (holdDuration == ms)
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { DetectionState.holdConfirmationDurationMs = ms },
                                color = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155)
                                )
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${ms}ms",
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color.White else Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PreStartParametersConfigCard(isRunning: Boolean) {
    val tapX by DetectionState.tapXFlow.collectAsStateWithLifecycle()
    val tapY by DetectionState.tapYFlow.collectAsStateWithLifecycle()
    val holdDuration by DetectionState.holdConfirmationDurationMsFlow.collectAsStateWithLifecycle()
    val targetX by DetectionState.targetXFlow.collectAsStateWithLifecycle()
    val targetY by DetectionState.targetYFlow.collectAsStateWithLifecycle()

    var tapXText by remember(tapX) { mutableStateOf(tapX.toInt().toString()) }
    var tapYText by remember(tapY) { mutableStateOf(tapY.toInt().toString()) }
    var holdDurationText by remember(holdDuration) { mutableStateOf(holdDuration.toString()) }
    var targetXText by remember(targetX) { mutableStateOf(targetX.toString()) }
    var targetYText by remember(targetY) { mutableStateOf(targetY.toString()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F172A)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (!isRunning) Color(0xFF38BDF8) else Color(0xFF334155)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TÙY CHỈNH THÔNG SỐ KHỞI CHẠY",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    Text(
                        text = "Thiết lập trước khi bấm START DETECTION",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
                Surface(
                    color = if (!isRunning) Color(0xFF065F46) else Color(0xFF374151),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (!isRunning) "SẴN SÀNG CHỈNH SỬA ✓" else "🔒 ĐANG CHẠY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (!isRunning) Color(0xFF34D399) else Color(0xFF9CA3AF),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // 1. Edge Trigger (X, Y) Coordinates
            Text(
                text = "1. TỌA ĐỘ PHẢN XẠ EDGE TRIGGER TAP (X, Y):",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE2E8F0)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = tapXText,
                    onValueChange = { newText ->
                        tapXText = newText
                        newText.toFloatOrNull()?.let { DetectionState.TAP_X = it }
                    },
                    label = { Text("Edge Trigger X", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    enabled = !isRunning,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF475569)
                    )
                )

                OutlinedTextField(
                    value = tapYText,
                    onValueChange = { newText ->
                        tapYText = newText
                        newText.toFloatOrNull()?.let { DetectionState.TAP_Y = it }
                    },
                    label = { Text("Edge Trigger Y", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    enabled = !isRunning,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF475569)
                    )
                )
            }

            // Quick preset chips for Edge Trigger
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .clickable(enabled = !isRunning) {
                            DetectionState.TAP_X = DetectionState.DEFAULT_TAP_X
                            DetectionState.TAP_Y = DetectionState.DEFAULT_TAP_Y
                            tapXText = DetectionState.DEFAULT_TAP_X.toInt().toString()
                            tapYText = DetectionState.DEFAULT_TAP_Y.toInt().toString()
                        },
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569))
                ) {
                    Text(
                        text = "Mặc định (${DetectionState.DEFAULT_TAP_X.toInt()}, ${DetectionState.DEFAULT_TAP_Y.toInt()})",
                        fontSize = 10.sp,
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    modifier = Modifier
                        .clickable(enabled = !isRunning) {
                            DetectionState.TAP_X = 1205f
                            DetectionState.TAP_Y = 479f
                            tapXText = "1205"
                            tapYText = "479"
                        },
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569))
                ) {
                    Text(
                        text = "Gốc (1205, 479)",
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // 2. Thời gian Hold (ms)
            Text(
                text = "2. THỜI GIAN HOLD XÁC NHẬN (HOLD DURATION MS):",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE2E8F0)
            )

            OutlinedTextField(
                value = holdDurationText,
                onValueChange = { newText ->
                    holdDurationText = newText
                    newText.toLongOrNull()?.let { DetectionState.holdConfirmationDurationMs = it }
                },
                label = { Text("Thời gian Hold (ms)", fontSize = 11.sp) },
                supportingText = {
                    Text(
                        "Tùy chỉnh bất kỳ số ms nào (0ms = phản xạ tức thì, 200, 500, 600, 800, 1000...)",
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRunning,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF38BDF8),
                    unfocusedBorderColor = Color(0xFF475569)
                )
            )

            // Hold preset chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(0L, 200L, 500L, 600L, 800L, 1000L).forEach { ms ->
                    val isSelected = (holdDuration == ms)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !isRunning) {
                                DetectionState.holdConfirmationDurationMs = ms
                                holdDurationText = ms.toString()
                            },
                        color = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155)
                        )
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${ms}ms",
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            // 3. Sensor Coordinates (X, Y)
            Text(
                text = "3. TỌA ĐỘ CẢM BIẾN NHẬN DIỆN MÀU (SENSOR X, Y):",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE2E8F0)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = targetXText,
                    onValueChange = { newText ->
                        targetXText = newText
                        newText.toIntOrNull()?.let { DetectionState.TARGET_X = it }
                    },
                    label = { Text("Sensor X", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    enabled = !isRunning,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF475569)
                    )
                )

                OutlinedTextField(
                    value = targetYText,
                    onValueChange = { newText ->
                        targetYText = newText
                        newText.toIntOrNull()?.let { DetectionState.TARGET_Y = it }
                    },
                    label = { Text("Sensor Y", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    enabled = !isRunning,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF475569)
                    )
                )
            }

            // 4. BỘ TỐI ƯU HÓA ĐỘ TRỄ SIÊU TỐC (GAIN ~80-110ms)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF0284C7).copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚡ TỐI ƯU ĐỘ TRỄ SIÊU TỐC (GAIN ~80-110ms)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                        Surface(
                            color = Color(0xFF065F46),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "5 FIXES ACTIVE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Fix 1: Downscale Capture
                    val downscaleFactor by DetectionState.captureDownscaleFactorFlow.collectAsStateWithLifecycle()
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "1. Downscale Capture (Giảm GPU composite ~40-60ms):",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE2E8F0)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                2 to "1/2 (802×360) ⭐",
                                4 to "1/4 (401×180)",
                                1 to "1/1 (1604×720)"
                            ).forEach { (factor, label) ->
                                val isSelected = (downscaleFactor == factor)
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = !isRunning) {
                                            DetectionState.captureDownscaleFactor = factor
                                        },
                                    color = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155)
                                    )
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 9.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color.White else Color(0xFF94A3B8)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Fix 2: Loop Delay (8ms / 0ms khi RED)
                    val loopDelay by DetectionState.loopDelayMsFlow.collectAsStateWithLifecycle()
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "2. Loop Interval: ${loopDelay}ms (~125Hz) + 0ms khi RED (~15-22ms gain):",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE2E8F0)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(4L, 8L, 16L, 30L).forEach { ms ->
                                val isSelected = (loopDelay == ms)
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = !isRunning) {
                                            DetectionState.loopDelayMs = ms
                                        },
                                    color = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155)
                                    )
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${ms}ms",
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color.White else Color(0xFF94A3B8)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Fix 3: Bỏ sqrt()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "3. Bỏ sqrt(): So sánh dist² <= r² (~3-5ms gain)",
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Surface(
                            color = Color(0xFF065F46),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "ZERO SQRT ✓",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Fix 4: Stride 2
                    val roiStride by DetectionState.roiScanStrideFlow.collectAsStateWithLifecycle()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "4. Quét Stride 2: Chỉ 100px thay vì 400px (~5-8ms gain)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(2 to "Step 2 ⭐", 1 to "Step 1").forEach { (step, text) ->
                                val isSelected = (roiStride == step)
                                Surface(
                                    modifier = Modifier.clickable(enabled = !isRunning) {
                                        DetectionState.roiScanStride = step
                                    },
                                    color = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                                    shape = RoundedCornerShape(4.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) Color(0xFF38BDF8) else Color(0xFF475569))
                                ) {
                                    Text(
                                        text = text,
                                        fontSize = 9.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Fix 5: Tắt Log Hot-Path
                    val debugLog by DetectionState.debugLoggingEnabledFlow.collectAsStateWithLifecycle()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "5. Tắt Log Hot-Path (~5-15ms JNI gain)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                text = if (debugLog) "Đang bật log (chậm hơn)" else "Đã tắt log (tối đa tốc độ)",
                                fontSize = 9.sp,
                                color = if (debugLog) Color(0xFFFBBF24) else Color(0xFF34D399)
                            )
                        }
                        Switch(
                            checked = debugLog,
                            onCheckedChange = { DetectionState.debugLoggingEnabled = it }
                        )
                    }

                    // Cơ chế vuốt Y + 20 sau khi tap (chống tâm nháy đỏ gây nhiễu)
                    val isPostTapSwipeEnabled by DetectionState.isPostTapSwipeEnabledFlow.collectAsStateWithLifecycle()
                    val postTapDeltaY by DetectionState.postTapFlickDeltaYFlow.collectAsStateWithLifecycle()
                    val useMotorOrigin by DetectionState.useMotorForPostTapSwipeFlow.collectAsStateWithLifecycle()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "6. Tự động vuốt Y + 20 sau khi Tap (Chống nháy đỏ)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFBBF24)
                                )
                                Text(
                                    text = "Kéo góc nhìn dịch chuyển Y + 20 ngay sau khi tap để phá vỡ tâm chớp đỏ, tránh nhiễu lặp phản xạ",
                                    fontSize = 9.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Switch(
                                checked = isPostTapSwipeEnabled,
                                onCheckedChange = { DetectionState.isPostTapSwipeEnabled = it }
                            )
                        }

                        if (isPostTapSwipeEnabled) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(15f to "+15px", 20f to "+20px ⭐", 30f to "+30px", 40f to "+40px").forEach { (dy, label) ->
                                    val isSelected = (postTapDeltaY == dy)
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(enabled = !isRunning) {
                                                DetectionState.postTapFlickDeltaY = dy
                                            },
                                        color = if (isSelected) Color(0xFFD97706) else Color(0xFF1E293B),
                                        shape = RoundedCornerShape(4.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isSelected) Color(0xFFFBBF24) else Color(0xFF334155)
                                        )
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 5.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 9.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) Color.White else Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    true to "Gốc: Motor (1205, 479) ⭐",
                                    false to "Gốc: Điểm Tap (597, 497)"
                                ).forEach { (useMotor, title) ->
                                    val isSelected = (useMotorOrigin == useMotor)
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(enabled = !isRunning) {
                                                DetectionState.useMotorForPostTapSwipe = useMotor
                                            },
                                        color = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                                        shape = RoundedCornerShape(4.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155)
                                        )
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 5.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = title,
                                                fontSize = 9.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) Color.White else Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                }
                            }

                            // Test post-tap flick
                            OutlinedButton(
                                onClick = {
                                    val startX = if (useMotorOrigin) DetectionState.PERIPHERAL_MOTOR_X else DetectionState.TAP_X
                                    val startY = if (useMotorOrigin) DetectionState.PERIPHERAL_MOTOR_Y else DetectionState.TAP_Y
                                    NeuralAccessibilityService.dispatchSwipe(
                                        startX = startX,
                                        startY = startY,
                                        endX = startX,
                                        endY = startY + postTapDeltaY,
                                        durationMs = 35L
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color(0xFFFBBF24).copy(alpha = 0.1f)
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFBBF24))
                            ) {
                                Text(
                                    "⚡ THỬ NGHIỆM VUỐT Y + ${postTapDeltaY.toInt()}PX (CHỐNG NHÁY ĐỎ)",
                                    fontSize = 10.sp,
                                    color = Color(0xFFFBBF24),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // 7. BỘ LỌC CHỐNG NHẦM DẢI TRẮNG XANH & TÂM CAM / VÀNG CAM (THEO HÌNH ẢNH)
                    val rejectOrangeYellow by DetectionState.rejectOrangeYellowFilterEnabledFlow.collectAsStateWithLifecycle()
                    val rejectBluishWhite by DetectionState.rejectBluishWhiteFilterEnabledFlow.collectAsStateWithLifecycle()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "7. BỘ LỌC TRIỆT TIÊU NHIỄU SẮC TỐ (NÂNG CẤP THEO ẢNH):",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )

                        // Filter A: Reject Orange / Yellow-Orange Reticle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "• Ngăn chặn tâm màu cam - vàng cam",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE2E8F0)
                                )
                                Text(
                                    text = "Loại bỏ vòng ngắm cam/vàng cam, chỉ nhận diện màu ĐỎ mục tiêu thực sự",
                                    fontSize = 9.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Switch(
                                checked = rejectOrangeYellow,
                                onCheckedChange = { DetectionState.rejectOrangeYellowFilterEnabled = it }
                            )
                        }

                        // Filter B: Reject Bluish-White Band
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "• Ngăn chặn dải màu trắng xanh",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE2E8F0)
                                )
                                Text(
                                    text = "Loại bỏ nền mây/sương trắng xanh, chỉ kích hoạt khi có sắc ấm da nhợt",
                                    fontSize = 9.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Switch(
                                checked = rejectBluishWhite,
                                onCheckedChange = { DetectionState.rejectBluishWhiteFilterEnabled = it }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MultiPointMacroReflexCard(
    isAccessibilityActive: Boolean,
    isRunning: Boolean
) {
    val isMacroEnabled by DetectionState.isMacroModeEnabledFlow.collectAsStateWithLifecycle()
    val profiles by DetectionState.macroProfilesFlow.collectAsStateWithLifecycle()
    val activeProfileId by DetectionState.activeProfileIdFlow.collectAsStateWithLifecycle()
    val macroStatus by DetectionState.macroExecutionStatusFlow.collectAsStateWithLifecycle()

    val activeProfile = profiles.find { it.id == activeProfileId } ?: profiles.firstOrNull() ?: getDefaultMacroProfiles().first()

    var showAddStepDialog by remember { mutableStateOf(false) }
    var showCreateProfileDialog by remember { mutableStateOf(false) }
    var showRecordDialog by remember { mutableStateOf(false) }
    var isTestRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F172A)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (macroStatus.isExecuting) Color(0xFF38BDF8) else Color(0xFF334155)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "MACRO PHẢN XẠ NHIỀU ĐIỂM",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF0284C7).copy(alpha = 0.25f)
                        ) {
                            Text(
                                text = "OPPO / XIAOMI TURBO",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "Phản xạ chuỗi Chạm / Vuốt đa điểm mẫu khi phát hiện RED",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
                Switch(
                    checked = isMacroEnabled,
                    onCheckedChange = { DetectionState.isMacroModeEnabled = it }
                )
            }

            // Real-time Macro execution HUD
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (macroStatus.isExecuting) Color(0xFF0C4A6E) else Color(0xFF1E293B),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (macroStatus.isExecuting) Color(0xFF38BDF8) else Color(0xFF334155)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (macroStatus.isExecuting) "⚡ ĐANG THỰC THI MACRO" else "TRẠNG THÁI MACRO",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (macroStatus.isExecuting) Color(0xFF7DD3FC) else Color(0xFF94A3B8)
                        )
                        Text(
                            text = macroStatus.statusMessage,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (macroStatus.isExecuting) Color.White else Color(0xFFCBD5E1)
                        )
                    }
                    Text(
                        text = "Đã chạy: ${macroStatus.totalExecutions}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }
            }

            // Profile Tabs
            Text(
                text = "HỒ SƠ MACRO (PROFILES):",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE2E8F0)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        profiles.forEach { profile ->
                            val isSelected = (profile.id == activeProfile.id)
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        DetectionState.selectProfile(profile.id)
                                    },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155)
                                )
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = profile.name.take(16),
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { showCreateProfileDialog = true },
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1E293B),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                    }
                }
            }

            Text(
                text = "📋 ${activeProfile.description}",
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
            )

            // Step sequence header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CHUỖI BƯỚC PHẢN XẠ (${activeProfile.steps.size} BƯỚC):",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE2E8F0)
                )

                if (profiles.size > 1 && !activeProfile.isDefault) {
                    Text(
                        text = "Xóa Profile này",
                        fontSize = 10.sp,
                        color = Color(0xFFEF4444),
                        modifier = Modifier.clickable {
                            DetectionState.deleteProfile(activeProfile.id)
                        }
                    )
                }
            }

            // Step list
            if (activeProfile.steps.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1E293B)
                ) {
                    Text(
                        text = "Chưa có bước thao tác nào. Nhấn \"Thêm Điểm Phản Xạ\" hoặc \"Ghi Lại Mẫu\" bên dưới.",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    activeProfile.steps.forEachIndexed { index, step ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E293B),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFF0284C7),
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${index + 1}",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (step.type == MacroActionType.TAP) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFFF59E0B).copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = if (step.type == MacroActionType.TAP) "👆 TAP" else "↔️ SWIPE",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (step.type == MacroActionType.TAP) Color(0xFF34D399) else Color(0xFFFBBF24),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    Column {
                                        Text(
                                            text = step.label,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                        val coordText = if (step.type == MacroActionType.TAP) {
                                            "Tọa độ: (${step.x.toInt()}, ${step.y.toInt()}) • ${step.durationMs}ms"
                                        } else {
                                            "Từ (${step.x.toInt()}, ${step.y.toInt()}) → (${step.endX.toInt()}, ${step.endY.toInt()}) • ${step.durationMs}ms"
                                        }
                                        Text(
                                            text = "$coordText | Nghỉ: ${step.delayAfterMs}ms",
                                            fontSize = 10.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { DetectionState.removeStepFromActiveProfile(step.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Text("✕", fontSize = 12.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Buttons: Add Step & Record User Macro
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showAddStepDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF0284C7).copy(alpha = 0.15f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8))
                ) {
                    Text(
                        "➕ THÊM ĐIỂM",
                        fontSize = 10.sp,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = {
                        DetectionState.startMacroRecording()
                        showRecordDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFFEF4444).copy(alpha = 0.15f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444))
                ) {
                    Text(
                        "🔴 GHI MẪU USER",
                        fontSize = 10.sp,
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Test execute Macro button
            OutlinedButton(
                onClick = {
                    scope.launch {
                        isTestRunning = true
                        val success = NeuralAccessibilityService.dispatchMacro(
                            profile = activeProfile,
                            onCompleted = {
                                isTestRunning = false
                            }
                        )
                        if (!success) {
                            Toast.makeText(context, "Accessibility Service chưa bật hoặc đang bận!", Toast.LENGTH_SHORT).show()
                            isTestRunning = false
                        }
                    }
                },
                enabled = !isTestRunning,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF10B981).copy(alpha = 0.15f)
                ),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF10B981))
            ) {
                Text(
                    text = if (isTestRunning) "⏳ ĐANG PHẢN XẠ THỰC THI MACRO..." else "⚡ CHẠY THỬ MACRO NGAY (${activeProfile.name})",
                    color = Color(0xFF34D399),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
    }

    if (showAddStepDialog) {
        AddMacroStepDialog(
            onDismiss = { showAddStepDialog = false },
            onAddStep = { step ->
                DetectionState.addStepToActiveProfile(step)
                showAddStepDialog = false
            }
        )
    }

    if (showCreateProfileDialog) {
        CreateMacroProfileDialog(
            onDismiss = { showCreateProfileDialog = false },
            onCreate = { name, desc ->
                val newProfile = MacroProfile(
                    name = name,
                    description = desc,
                    steps = listOf(
                        MacroStep(
                            type = MacroActionType.TAP,
                            x = DetectionState.TAP_X,
                            y = DetectionState.TAP_Y,
                            durationMs = 35L,
                            delayAfterMs = 20L,
                            label = "Tap Khởi đầu"
                        )
                    )
                )
                DetectionState.addProfile(newProfile)
                showCreateProfileDialog = false
            }
        )
    }

    if (showRecordDialog) {
        RecordUserMacroDialog(
            onDismiss = {
                DetectionState.cancelMacroRecording()
                showRecordDialog = false
            },
            onSave = { name ->
                DetectionState.stopMacroRecordingAndSave(name)
                showRecordDialog = false
            }
        )
    }
}

@Composable
fun AddMacroStepDialog(
    onDismiss: () -> Unit,
    onAddStep: (MacroStep) -> Unit
) {
    var actionType by remember { mutableStateOf(MacroActionType.TAP) }
    var xText by remember { mutableStateOf("597") }
    var yText by remember { mutableStateOf("497") }
    var endXText by remember { mutableStateOf("1205") }
    var endYText by remember { mutableStateOf("499") }
    var durationText by remember { mutableStateOf("35") }
    var delayText by remember { mutableStateOf("25") }
    var labelText by remember { mutableStateOf("Thao tác mới") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8))
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "➕ THÊM ĐIỂM THAO TÁC MACRO",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8),
                    fontSize = 13.sp
                )

                // Select Action Type
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(MacroActionType.TAP to "👆 Chạm (Tap)", MacroActionType.SWIPE to "↔️ Vuốt (Swipe)").forEach { (type, label) ->
                        val isSelected = (actionType == type)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { actionType = type },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155)
                            )
                        ) {
                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.White else Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }

                // Preset coordinate buttons
                Text("Vị trí mẫu nhanh:", fontSize = 10.sp, color = Color(0xFF94A3B8))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "Nút Bắn (597, 497)" to Pair(597f, 497f),
                        "Cơ quan (1205, 479)" to Pair(1205f, 479f),
                        "Nút Ngồi (1380, 560)" to Pair(1380f, 560f)
                    ).forEach { (name, coords) ->
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    xText = coords.first.toInt().toString()
                                    yText = coords.second.toInt().toString()
                                    if (actionType == MacroActionType.SWIPE) {
                                        endXText = coords.first.toInt().toString()
                                        endYText = (coords.second + 20).toInt().toString()
                                    }
                                },
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF1E293B),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Text(
                                text = name,
                                fontSize = 8.sp,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.padding(4.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                // Input coordinates
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = xText,
                        onValueChange = { xText = it },
                        label = { Text("Tọa độ X", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = yText,
                        onValueChange = { yText = it },
                        label = { Text("Tọa độ Y", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                if (actionType == MacroActionType.SWIPE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = endXText,
                            onValueChange = { endXText = it },
                            label = { Text("Đích đến X", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = endYText,
                            onValueChange = { endYText = it },
                            label = { Text("Đích đến Y", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it },
                        label = { Text("Thời gian (ms)", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = delayText,
                        onValueChange = { delayText = it },
                        label = { Text("Nghỉ sau đó (ms)", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it },
                    label = { Text("Tên/Mô tả bước", fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("HỦY", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            val x = xText.toFloatOrNull() ?: 597f
                            val y = yText.toFloatOrNull() ?: 497f
                            val endX = endXText.toFloatOrNull() ?: x
                            val endY = endYText.toFloatOrNull() ?: (y + 20f)
                            val duration = durationText.toLongOrNull() ?: 35L
                            val delay = delayText.toLongOrNull() ?: 20L
                            onAddStep(
                                MacroStep(
                                    type = actionType,
                                    x = x,
                                    y = y,
                                    endX = endX,
                                    endY = endY,
                                    durationMs = duration,
                                    delayAfterMs = delay,
                                    label = labelText.ifEmpty { if (actionType == MacroActionType.TAP) "Chạm ($x, $y)" else "Vuốt ($x, $y) -> ($endX, $endY)" }
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Text("THÊM BƯỚC", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun CreateMacroProfileDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("Macro Mới") }
    var desc by remember { mutableStateOf("Tùy chỉnh cá nhân") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "TẠO HỒ SƠ MACRO MỚI",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8),
                    fontSize = 13.sp
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tên Macro", fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Mô tả tác dụng", fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("HỦY", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            if (name.isNotEmpty()) onCreate(name, desc)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Text("TẠO PROFILES", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun RecordUserMacroDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val recordingState by DetectionState.macroRecordingStateFlow.collectAsStateWithLifecycle()
    var profileName by remember { mutableStateOf("Macro Ghi Mẫu ${System.currentTimeMillis() % 1000}") }
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var touchStartTime by remember { mutableStateOf(0L) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFEF4444))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🔴 GHI PHẢN XẠ MẪU TỪ USER",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF4444),
                        fontSize = 13.sp
                    )
                    Text(
                        text = "${recordingState.recordedSteps.size} bước",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Text(
                    text = "Chạm (Tap) hoặc Vuốt (Swipe) trực tiếp lên vùng cảm ứng bên dưới để ghi lại thao tác mẫu chuẩn xác của bạn:",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )

                // Interactive Touch Canvas Pad
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color(0xFF1E293B), RoundedCornerShape(10.dp))
                        .border(1.5.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = { offset ->
                                    val startX = offset.x
                                    val startY = offset.y
                                    val startTime = System.currentTimeMillis()
                                    tryAwaitRelease()
                                    val elapsed = (System.currentTimeMillis() - startTime).coerceIn(25L, 300L)
                                    DetectionState.recordAction(
                                        MacroStep(
                                            type = MacroActionType.TAP,
                                            x = startX,
                                            y = startY,
                                            durationMs = elapsed,
                                            delayAfterMs = 25L,
                                            label = "Tap (${startX.toInt()}, ${startY.toInt()})"
                                        )
                                    )
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    touchStartX = offset.x
                                    touchStartY = offset.y
                                    touchStartTime = System.currentTimeMillis()
                                },
                                onDragEnd = {
                                    // Finished drag gesture
                                }
                            ) { change, _ ->
                                change.consume()
                                val currentX = change.position.x
                                val currentY = change.position.y
                                val dx = currentX - touchStartX
                                val dy = currentY - touchStartY
                                val distSq = dx * dx + dy * dy
                                if (distSq > 400f) {
                                    val elapsed = (System.currentTimeMillis() - touchStartTime).coerceIn(30L, 400L)
                                    DetectionState.recordAction(
                                        MacroStep(
                                            type = MacroActionType.SWIPE,
                                            x = touchStartX,
                                            y = touchStartY,
                                            endX = currentX,
                                            endY = currentY,
                                            durationMs = elapsed,
                                            delayAfterMs = 25L,
                                            label = "Vuốt (${touchStartX.toInt()}, ${touchStartY.toInt()}) → (${currentX.toInt()}, ${currentY.toInt()})"
                                        )
                                    )
                                    touchStartX = currentX
                                    touchStartY = currentY
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎮 VÙNG CẢM ỨNG GHI MẪU", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                        Text("Chạm hoặc Vuốt tại đây", fontSize = 10.sp, color = Color(0xFF64748B))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (recordingState.recordedSteps.isEmpty()) "Chưa có thao tác nào" else "Đã ghi ${recordingState.recordedSteps.size} bước phản xạ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4ADE80)
                        )
                    }
                }

                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text("Tên Macro", fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("HỦY", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    }
                    Button(
                        onClick = { onSave(profileName) },
                        modifier = Modifier.weight(1f),
                        enabled = recordingState.recordedSteps.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text("💾 LƯU MACRO", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionsCard(
    hasOverlayPermission: Boolean,
    hasNotificationPermission: Boolean,
    isAccessibilityActive: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "REQUIRED PERMISSIONS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Auto-refreshes when returning to app",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Recheck Permissions",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Overlay Permission Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (hasOverlayPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (hasOverlayPermission) Color(0xFF00C853) else Color(0xFFFFAB00),
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "Floating Overlay (WindowManager)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (hasOverlayPermission) "Permission Granted" else "Tap Grant to enable in Settings",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasOverlayPermission) Color(0xFF00C853) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!hasOverlayPermission) {
                    OutlinedButton(
                        onClick = onRequestOverlay,
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Grant", fontSize = 11.sp)
                    }
                } else {
                    Surface(
                        color = Color(0xFF00C853).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Granted ✓",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00C853),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Notification Permission Row
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (hasNotificationPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (hasNotificationPermission) Color(0xFF00C853) else Color(0xFFFFAB00),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Foreground Notification",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (!hasNotificationPermission) {
                        OutlinedButton(
                            onClick = onRequestNotification,
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("Grant", fontSize = 11.sp)
                        }
                    } else {
                        Text(
                            text = "Granted",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00C853)
                        )
                    }
                }
            }

            // Accessibility Permission Row for Motor Tap Gesture
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (isAccessibilityActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isAccessibilityActive) Color(0xFF00C853) else Color(0xFFFFAB00),
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "Motor Gesture (Accessibility Service)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isAccessibilityActive) "Connected & Ready to tap (597, 497)" else "Cần bật trong Trợ năng để phát tap",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isAccessibilityActive) Color(0xFF00C853) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!isAccessibilityActive) {
                    OutlinedButton(
                        onClick = onRequestAccessibility,
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Grant", fontSize = 11.sp)
                    }
                } else {
                    Surface(
                        color = Color(0xFF00C853).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Ready ✓",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00C853),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SimulationCard(
    onShowOverlay: () -> Unit,
    onHideOverlay: () -> Unit,
    onSimulateStatus: (DetectionResult, Int, Int, Int, Int, Int, Int, String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "BỘ THỬ NGHIỆM TÌNH HUỐNG (TEST SUITE)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Thử nghiệm ngay các tình huống thực tế trong game để kiểm tra khả năng chống nhiễu:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Special test case: Center Red + Grass Background
            OutlinedButton(
                onClick = {
                    onShowOverlay()
                    // Simulation: Center is red (R=220, G=30, B=30) with 6 core red pixels,
                    // but the background has 120 GREEN grass pixels!
                    onSimulateStatus(
                        DetectionResult.RED,
                        220, 30, 30,
                        15, 120, 6,
                        "Ưu tiên lõi tâm (Core Red 6px, Cỏ nền 120px)"
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFFFF1744).copy(alpha = 0.12f)
                ),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFF1744))
            ) {
                Text(
                    "🔥 TEST: TÂM ĐỎ + NỀN CỎ XANH (120px Grass)",
                    color = Color(0xFFFF1744),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            val tapX by DetectionState.tapXFlow.collectAsStateWithLifecycle()
            val tapY by DetectionState.tapYFlow.collectAsStateWithLifecycle()
            val holdDuration by DetectionState.holdConfirmationDurationMsFlow.collectAsStateWithLifecycle()

            // Test Motor Tap at dynamic coordinates
            OutlinedButton(
                onClick = {
                    val dispatched = NeuralAccessibilityService.dispatchTap(
                        DetectionState.TAP_X,
                        DetectionState.TAP_Y
                    )
                    if (!dispatched) {
                        // Service not connected
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF38BDF8).copy(alpha = 0.12f)
                ),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8))
            ) {
                Text(
                    "⚡ TEST PHẢN XẠ MOTOR TAP (${tapX.toInt()}, ${tapY.toInt()})",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            val scope = rememberCoroutineScope()
            var isSimulatingHold by remember { mutableStateOf(false) }

            // Test Fly / Transient Flicker (150ms) -> Must be rejected (0 TAP)
            OutlinedButton(
                onClick = {
                    onShowOverlay()
                    scope.launch {
                        // Baseline
                        onSimulateStatus(DetectionResult.SCANNING, 80, 80, 80, 0, 0, 0, "Calibrating baseline")
                        kotlinx.coroutines.delay(40L)
                        // Fly / Dust flickers red for only 150ms (< hold duration)
                        onSimulateStatus(DetectionResult.RED, 230, 20, 20, 160, 0, 10, "Ruồi bay nháy đỏ 150ms")
                        kotlinx.coroutines.delay(150L)
                        // Target lost, reverts to scanning!
                        onSimulateStatus(DetectionResult.SCANNING, 80, 80, 80, 0, 0, 0, "Ruồi bay mất -> Hủy bỏ reflex (0 TAP)")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFFEAB308).copy(alpha = 0.12f)
                ),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFEAB308))
            ) {
                Text(
                    "🪰 TEST RUỒI BAY NHÁY ĐỎ (150ms) -> CHẶN, 0 TAP",
                    color = Color(0xFFFBBF24),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            // Test Sustained Hold -> Must be confirmed and trigger TAP!
            OutlinedButton(
                onClick = {
                    onShowOverlay()
                    scope.launch {
                        isSimulatingHold = true
                        // Baseline
                        onSimulateStatus(DetectionResult.SCANNING, 80, 80, 80, 0, 0, 0, "Calibrating baseline")
                        kotlinx.coroutines.delay(40L)
                        // Continuous steady RED until holdDuration + 50ms
                        val targetMs = (holdDuration + 50L).coerceAtLeast(100L)
                        val start = System.currentTimeMillis()
                        while (System.currentTimeMillis() - start <= targetMs) {
                            val elapsed = System.currentTimeMillis() - start
                            onSimulateStatus(DetectionResult.RED, 235, 15, 15, 190, 0, 12, "Giữ tâm đỏ ổn định ($elapsed ms)")
                            kotlinx.coroutines.delay(35L)
                        }
                        isSimulatingHold = false
                    }
                },
                enabled = !isSimulatingHold,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF10B981).copy(alpha = 0.12f)
                ),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF10B981))
            ) {
                Text(
                    if (isSimulatingHold) "⏳ ĐANG GIỮ TÂM ĐỎ..." else "🎯 TEST GIỮ TÂM ĐỎ CHUẨN (${holdDuration}ms) -> KÍCH HOẠT TAP",
                    color = Color(0xFF34D399),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onShowOverlay()
                        onSimulateStatus(DetectionResult.RED, 220, 20, 20, 180, 0, 12, "Pure Red Target")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test RED", color = Color(0xFFFF1744), fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        onShowOverlay()
                        onSimulateStatus(DetectionResult.GREEN, 30, 210, 40, 0, 160, 0, "Friendly Target")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test GREEN", color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        onShowOverlay()
                        onSimulateStatus(DetectionResult.SCANNING, 80, 80, 80, 0, 0, 0, "No Target (Scanning)")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Scanning", fontWeight = FontWeight.Normal)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onShowOverlay,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Bật HUD nổi")
                }
                OutlinedButton(
                    onClick = onHideOverlay,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Tắt HUD nổi")
                }
            }
        }
    }
}
