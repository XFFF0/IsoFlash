package com.xfff0.isoflash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xfff0.isoflash.BurnViewModel
import com.xfff0.isoflash.DiskFormatter
import com.xfff0.isoflash.OpPhase
import com.xfff0.isoflash.UsbScsiDevice
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: BurnViewModel, onPickIso: () -> Unit) {
    var tabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("\u26A1 حرق ISO", "\uD83D\uDCC0 تهيئة القرص")

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
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = tabIndex,
                containerColor   = SurfaceCard,
                contentColor     = AccentTeal
            ) {
                tabs.forEachIndexed { i, title ->
                    Tab(
                        selected = tabIndex == i,
                        onClick  = { tabIndex = i; viewModel.reset() },
                        text     = { Text(title, fontSize = 14.sp,
                            color = if (tabIndex == i) AccentTeal else TextSecondary) }
                    )
                }
            }

            when (tabIndex) {
                0 -> BurnTab(viewModel, onPickIso)
                1 -> FormatTab(viewModel)
            }
        }
    }
}

// ── Burn ISO Tab ───────────────────────────────────────────────────────────────
@Composable
private fun BurnTab(viewModel: BurnViewModel, onPickIso: () -> Unit) {
    val selected = viewModel.selectedDrive
    val isoName  = viewModel.isoName
    val isoSize  = viewModel.isoSizeBytes
    val state    = viewModel.opState
    var confirm  by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("حرق ملف ISO مباشرة على USB بدون روت", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)

        DriveSection(viewModel)

        StepCard("2", "ملف ISO") {
            if (isoName == null) {
                Text("لم يُختر أي ملف.", color = TextSecondary)
            } else {
                Text(isoName, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                if (isoSize > 0) Text(fmtSize(isoSize), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onPickIso, colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardAlt, contentColor = AccentBlue)) {
                Text("\uD83D\uDCC2  تصفح")
            }
        }

        StepCard("3", "الحرق") {
            OpStatus(state)
            Spacer(Modifier.height(12.dp))
            val busy = state.phase == OpPhase.WORKING || state.phase == OpPhase.AWAITING_PERMISSION
            Button(
                onClick = { confirm = true },
                enabled = selected != null && isoName != null && !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = AccentTeal, contentColor = BgDeep,
                    disabledContainerColor = SurfaceCardAlt, disabledContentColor = TextSecondary
                )
            ) { Text("\u26A1  حرق", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (confirm) {
        ConfirmDialog(
            title = "\u26A0\uFE0F سيُمحى القرص بالكامل",
            body  = "سيتم كتابة \"${isoName ?: ""}\" مباشرة على \"${selected?.displayName ?: ""}\".\nكل البيانات الحالية ستُفقد نهائياً.",
            confirmText = "احرق الآن",
            onConfirm = { confirm = false; viewModel.startBurn() },
            onDismiss = { confirm = false }
        )
    }
}

// ── Format Tab ─────────────────────────────────────────────────────────────────
@Composable
private fun FormatTab(viewModel: BurnViewModel) {
    val state   = viewModel.opState
    val scheme  = viewModel.fmtScheme
    val fmtType = viewModel.fmtType
    var label   by remember { mutableStateOf(viewModel.fmtLabel) }
    var confirm by remember { mutableStateOf(false) }
    val busy    = state.phase == OpPhase.WORKING || state.phase == OpPhase.AWAITING_PERMISSION

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("تهيئة قرص USB أو مسح محتوياته كاملاً", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)

        DriveSection(viewModel)

        // Partition scheme
        StepCard("2", "نظام القسم") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(DiskFormatter.PartitionScheme.MBR, DiskFormatter.PartitionScheme.GPT).forEach { s ->
                    val sel = scheme == s
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) AccentTealDim else SurfaceCardAlt)
                            .border(1.dp, if (sel) AccentTeal else BorderSubtle, RoundedCornerShape(10.dp))
                            .clickable { viewModel.setScheme(s) }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.name, color = if (sel) AccentTeal else TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                if (s == DiskFormatter.PartitionScheme.MBR) "أقراص ≤ 2TB\nتوافق واسع" else "أقراص > 2TB\nحديث",
                                color = TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // Format type
        StepCard("3", "نوع العملية") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    DiskFormatter.FormatType.FAT32 to ("تهيئة FAT32" to "متوافق مع جميع الأجهزة"),
                    DiskFormatter.FormatType.WIPE  to ("مسح كامل (صفر)" to "حذف كل البيانات بالكتابة فوقها")
                ).forEach { (type, pair) ->
                    val (title, desc) = pair
                    val sel = fmtType == type
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) AccentTealDim else SurfaceCardAlt)
                            .border(1.dp, if (sel) AccentTeal else BorderSubtle, RoundedCornerShape(10.dp))
                            .clickable { viewModel.setFmtType(type) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = sel, onClick = { viewModel.setFmtType(type) },
                            colors = RadioButtonDefaults.colors(selectedColor = AccentTeal))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(title, color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text(desc, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Label (only for FAT32)
            if (fmtType == DiskFormatter.FormatType.FAT32) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.uppercase().take(11); viewModel.setLabel(label) },
                    label = { Text("تسمية القرص (Label)", color = TextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary,
                        focusedBorderColor   = AccentTeal,
                        unfocusedBorderColor = BorderSubtle,
                        cursorColor          = AccentTeal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Execute
        StepCard("4", "التنفيذ") {
            OpStatus(state)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { confirm = true },
                enabled = viewModel.selectedDrive != null && !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor         = if (fmtType == DiskFormatter.FormatType.WIPE) StatusError else AccentTeal,
                    contentColor           = BgDeep,
                    disabledContainerColor = SurfaceCardAlt,
                    disabledContentColor   = TextSecondary
                )
            ) {
                val icon = if (fmtType == DiskFormatter.FormatType.WIPE) "\uD83D\uDDD1\uFE0F" else "\uD83D\uDCC0"
                Text("$icon  ${if (fmtType == DiskFormatter.FormatType.WIPE) "مسح" else "تهيئة"}",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (confirm) {
        val isDrive = viewModel.selectedDrive
        ConfirmDialog(
            title = "\u26A0\uFE0F تأكيد العملية",
            body  = "سيتم ${if (fmtType == DiskFormatter.FormatType.WIPE) "مسح كل محتويات" else "تهيئة"} " +
                    "\"${isDrive?.displayName ?: ""}\" بصيغة ${if (fmtType == DiskFormatter.FormatType.WIPE) "صفر كامل" else "FAT32 (${scheme.name})"}.\n\nكل البيانات ستُفقد نهائياً.",
            confirmText = "نعم، نفّذ",
            onConfirm   = { confirm = false; viewModel.startFormat() },
            onDismiss   = { confirm = false }
        )
    }
}

// ── Shared composables ──────────────────────────────────────────────────────────
@Composable
private fun DriveSection(viewModel: BurnViewModel) {
    val drives   = viewModel.drives
    val selected = viewModel.selectedDrive
    StepCard("1", "اختر القرص") {
        if (drives.isEmpty()) {
            Text("لا توجد أقراص USB. صل القرص ثم اضغط تحديث.", color = TextSecondary, textAlign = TextAlign.Start)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                drives.forEach { drive ->
                    DriveRow(drive, selected?.usbDevice?.deviceId == drive.usbDevice.deviceId) { viewModel.selectDrive(drive) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { viewModel.refreshDrives() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentTeal)) {
            Text("\u21BA  تحديث")
        }
    }
}

@Composable
private fun OpStatus(state: com.xfff0.isoflash.OpState) {
    when (state.phase) {
        OpPhase.AWAITING_PERMISSION, OpPhase.WORKING -> {
            Text(state.message, color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress   = state.progress,
                modifier   = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color      = AccentTeal,
                trackColor = SurfaceCardAlt
            )
            if (state.speedMbPerSec > 0.0 || state.progress > 0f) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${(state.progress * 100).toInt()}%", color = TextPrimary, fontSize = 13.sp)
                    if (state.speedMbPerSec > 0.0)
                        Text(String.format(Locale.US, "%.1f MB/s", state.speedMbPerSec), color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
        OpPhase.DONE   -> Text("\u2705  ${state.message}", color = StatusSuccess)
        OpPhase.FAILED -> Text("\u274C  ${state.message}", color = StatusError)
        OpPhase.IDLE   -> Text("اختر القرص والإعدادات ثم ابدأ.", color = TextSecondary)
    }
}

@Composable
private fun StepCard(num: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(SurfaceCard, SurfaceCardAlt)))
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(7.dp)).background(AccentTealDim).padding(horizontal = 7.dp, vertical = 2.dp)) {
                Text(num, color = AccentTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun DriveRow(drive: UsbScsiDevice, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AccentTealDim else SurfaceCardAlt)
            .border(1.dp, if (selected) AccentTeal else BorderSubtle, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("\uD83D\uDCBE", fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(drive.displayName, color = TextPrimary, fontWeight = FontWeight.Medium)
            Text("%04x:%04x".format(drive.usbDevice.vendorId, drive.usbDevice.productId),
                color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Text("\u2713", color = AccentTeal, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextPrimary) },
        text  = { Text(body, color = TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText, color = StatusError, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
        containerColor = SurfaceCard, titleContentColor = TextPrimary
    )
}

private fun fmtSize(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb > 1024) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}
