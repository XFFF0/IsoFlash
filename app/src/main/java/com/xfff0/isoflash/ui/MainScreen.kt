package com.xfff0.isoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfff0.isoflash.BurnPhase
import com.xfff0.isoflash.BurnViewModel
import com.xfff0.isoflash.UsbScsiDevice
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: BurnViewModel, onPickIso: () -> Unit) {
    val drives   = viewModel.drives
    val selected = viewModel.selectedDrive
    val isoName  = viewModel.selectedIsoName
    val isoSize  = viewModel.selectedIsoSizeBytes
    val state    = viewModel.uiState
    var showConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("\u26A1 IsoFlash", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = BgDeep, titleContentColor = TextPrimary
                )
            )
        },
        containerColor = BgDeep
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Text("حرق ملفات ISO على USB بدون روت", color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium)

            StepCard("1", "اختر القرص") {
                if (drives.isEmpty()) {
                    Text("لا توجد أقراص USB متصلة. صل القرص ثم اضغط تحديث.",
                        color = TextSecondary, textAlign = TextAlign.Start)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        drives.forEach { drive ->
                            DriveRow(
                                drive    = drive,
                                selected = selected?.usbDevice?.deviceId == drive.usbDevice.deviceId,
                                onClick  = { viewModel.selectDrive(drive) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { viewModel.refreshDrives() },
                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = AccentTeal)
                ) { Text("\u21BA  تحديث") }
            }

            StepCard("2", "اختر ملف ISO") {
                if (isoName == null) {
                    Text("لم يتم اختيار أي ملف.", color = TextSecondary)
                } else {
                    Text(isoName, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    if (isoSize > 0) Text(formatSize(isoSize), color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onPickIso,
                    colors  = ButtonDefaults.buttonColors(containerColor = SurfaceCardAlt, contentColor = AccentBlue)
                ) { Text("\uD83D\uDCC2  تصفح") }
            }

            StepCard("3", "الحرق") {
                when (state.phase) {
                    BurnPhase.AWAITING_PERMISSION, BurnPhase.WRITING -> {
                        Text(state.message, color = TextSecondary)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress   = state.progress,
                            modifier   = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color      = AccentTeal,
                            trackColor = SurfaceCardAlt
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${(state.progress * 100).toInt()}%", color = TextPrimary)
                            if (state.speedMbPerSec > 0.0)
                                Text(String.format(Locale.US, "%.1f MB/s", state.speedMbPerSec), color = TextSecondary)
                        }
                    }
                    BurnPhase.DONE   -> Text("\u2705  ${state.message}", color = StatusSuccess)
                    BurnPhase.FAILED -> Text("\u274C  ${state.message}", color = StatusError)
                    BurnPhase.IDLE   -> Text("اختر القرص والملف ثم اضغط حرق.", color = TextSecondary)
                }
                Spacer(Modifier.height(14.dp))
                val busy    = state.phase == BurnPhase.WRITING || state.phase == BurnPhase.AWAITING_PERMISSION
                val canBurn = selected != null && isoName != null && !busy
                Button(
                    onClick  = { showConfirm = true },
                    enabled  = canBurn,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape    = RoundedCornerShape(14.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = AccentTeal, contentColor = BgDeep,
                        disabledContainerColor = SurfaceCardAlt, disabledContentColor = TextSecondary
                    )
                ) { Text("\u26A1  حرق", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("\u26A0\uFE0F  تحذير: سيُمحى القرص بالكامل", color = TextPrimary) },
            text  = { Text(
                "ستُكتب \"${isoName ?: ""}\" على \"${selected?.displayName ?: ""}\".\nكل البيانات ستُفقد نهائياً.",
                color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; viewModel.startBurn() }) {
                    Text("نعم، احرق", color = StatusError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("إلغاء") } },
            containerColor = SurfaceCard, titleContentColor = TextPrimary
        )
    }
}

@Composable
private fun StepCard(num: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(SurfaceCard, SurfaceCardAlt)))
            .border(1.dp, BorderSubtle, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(AccentTealDim)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) { Text(num, color = AccentTeal, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            Spacer(Modifier.width(10.dp))
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun DriveRow(drive: UsbScsiDevice, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AccentTealDim else SurfaceCardAlt)
            .border(1.dp, if (selected) AccentTeal else BorderSubtle, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("\uD83D\uDCBE", fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(drive.displayName, color = TextPrimary, fontWeight = FontWeight.Medium)
            Text("%04x:%04x".format(drive.usbDevice.vendorId, drive.usbDevice.productId),
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Text("\u2713", color = AccentTeal, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb > 1024) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}
