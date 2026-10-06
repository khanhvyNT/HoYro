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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                            lastUpdateTimeMs = System.currentTimeMillis(),
                            triggerReason = reason
                        )
                    )
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
                text = "• Tọa độ tâm ngắm (Crosshair): X = ${DetectionState.TARGET_X}, Y = ${DetectionState.TARGET_Y}",
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
                            text = "Sensor: (801, 359)",
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
                            text = "TAP (${DetectionState.TAP_X.toInt()}, ${DetectionState.TAP_Y.toInt()})",
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

            // Test Motor Tap at (597, 497)
            OutlinedButton(
                onClick = {
                    val context = androidx.compose.ui.platform.AndroidUiDispatcher.CurrentThread // or LocalContext
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
                    "⚡ TEST PHẢN XẠ MOTOR TAP (597, 497)",
                    color = Color(0xFF38BDF8),
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
