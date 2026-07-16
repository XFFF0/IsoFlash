package com.xfff0.isoflash

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

enum class OpPhase { IDLE, AWAITING_PERMISSION, WORKING, DONE, FAILED }

data class OpState(
    val phase:         OpPhase = OpPhase.IDLE,
    val message:       String  = "",
    val progress:      Float   = 0f,
    val speedMbPerSec: Double  = 0.0
)

class BurnViewModel(application: Application) : AndroidViewModel(application) {

    // ── UI state ──────────────────────────────────────────────────────────────
    var drives       by mutableStateOf<List<UsbScsiDevice>>(emptyList()); private set
    var selectedDrive by mutableStateOf<UsbScsiDevice?>(null);           private set

    var isoUri       by mutableStateOf<Uri?>(null);                      private set
    var isoName      by mutableStateOf<String?>(null);                   private set
    var isoSizeBytes by mutableStateOf(0L);                              private set

    // Format options
    var fmtScheme    by mutableStateOf(DiskFormatter.PartitionScheme.MBR); private set
    var fmtType      by mutableStateOf(DiskFormatter.FormatType.FAT32);    private set
    var fmtLabel     by mutableStateOf("ISOFLASH");                        private set

    var opState      by mutableStateOf(OpState());                        private set

    // ── USB permission ────────────────────────────────────────────────────────
    private val usbManager get() =
        getApplication<Application>().getSystemService(Context.USB_SERVICE) as UsbManager

    private var permCb: ((Boolean) -> Unit)? = null

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            permCb?.invoke(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            permCb = null
        }
    }

    init {
        ContextCompat.registerReceiver(
            getApplication(), permReceiver, IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshDrives()
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { getApplication<Application>().unregisterReceiver(permReceiver) }
    }

    // ── Public actions ────────────────────────────────────────────────────────
    fun refreshDrives() {
        drives = UsbScsiDevice.findDevices(getApplication())
        if (selectedDrive != null &&
            drives.none { it.usbDevice.deviceId == selectedDrive!!.usbDevice.deviceId })
            selectedDrive = null
    }

    fun selectDrive(d: UsbScsiDevice) {
        selectedDrive = d
        if (opState.phase != OpPhase.WORKING) opState = OpState()
    }

    fun selectIso(uri: Uri) {
        val ctx = getApplication<Application>()
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                isoName      = if (ni >= 0) c.getString(ni) else "image.iso"
                isoSizeBytes = if (si >= 0) c.getLong(si) else 0L
            }
        }
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        isoUri = uri
        if (opState.phase != OpPhase.WORKING) opState = OpState()
    }

    fun setScheme(s: DiskFormatter.PartitionScheme) { fmtScheme = s }
    fun setFmtType(t: DiskFormatter.FormatType)     { fmtType   = t }
    fun setLabel(l: String)                          { fmtLabel  = l.take(11) }

    fun startBurn() {
        val drive = selectedDrive ?: return
        val uri   = isoUri        ?: return
        val ctx   = getApplication<Application>()

        viewModelScope.launch {
            opState = OpState(OpPhase.AWAITING_PERMISSION, "في انتظار إذن USB…")
            if (!requestPerm(drive.usbDevice)) {
                opState = OpState(OpPhase.FAILED, "تم رفض الإذن."); return@launch
            }
            opState = OpState(OpPhase.WORKING, "جاري التهيئة…")

            val totalBytes = isoSizeBytes.takeIf { it > 0 }
                ?: runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)

            withContext(Dispatchers.IO) {
                runCatching {
                    drive.init()
                    try {
                        val chunkBytes = UsbScsiDevice.BLOCK_SIZE * 128
                        val buf = ByteArray(chunkBytes)
                        val stream = ctx.contentResolver.openInputStream(uri) ?: error("لا يمكن فتح الملف")
                        stream.use { inp ->
                            var lba = 0L; var written = 0L
                            val t0 = System.currentTimeMillis(); var lastUi = 0L
                            while (true) {
                                val read = inp.read(buf); if (read <= 0) break
                                val aligned = ((read + 511) / 512) * 512
                                val chunk = if (aligned == buf.size) buf else ByteArray(aligned).also { System.arraycopy(buf, 0, it, 0, read) }
                                drive.writeBlocks(lba, chunk)
                                lba += aligned / 512; written += read
                                val now = System.currentTimeMillis()
                                if (now - lastUi > 150) {
                                    lastUi = now
                                    val sec = (now - t0) / 1000.0
                                    val wMb = written / 1_048_576.0
                                    val prog = if (totalBytes > 0) (written.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                                    withContext(Dispatchers.Main) {
                                        opState = OpState(OpPhase.WORKING, "جاري الحرق…", prog, if (sec > 0) wMb / sec else 0.0)
                                    }
                                }
                            }
                        }
                    } finally { drive.close() }
                }
            }.onSuccess {
                opState = OpState(OpPhase.DONE, "تم الحرق بنجاح ✓", 1f)
            }.onFailure {
                opState = OpState(OpPhase.FAILED, "فشل: ${it.message ?: it::class.simpleName}")
            }
        }
    }

    fun startFormat() {
        val drive = selectedDrive ?: return
        val opts  = DiskFormatter.Options(fmtScheme, fmtType, fmtLabel)

        viewModelScope.launch {
            opState = OpState(OpPhase.AWAITING_PERMISSION, "في انتظار إذن USB…")
            if (!requestPerm(drive.usbDevice)) {
                opState = OpState(OpPhase.FAILED, "تم رفض الإذن."); return@launch
            }
            opState = OpState(OpPhase.WORKING, "جاري العملية…")

            withContext(Dispatchers.IO) {
                runCatching {
                    drive.init()
                    try {
                        DiskFormatter.format(drive, opts) { prog, msg ->
                            val main = Dispatchers.Main
                            viewModelScope.launch(main) {
                                opState = OpState(OpPhase.WORKING, msg, prog)
                            }
                        }
                    } finally { drive.close() }
                }
            }.onSuccess {
                opState = OpState(OpPhase.DONE, "اكتملت العملية ✓", 1f)
            }.onFailure {
                opState = OpState(OpPhase.FAILED, "فشل: ${it.message ?: it::class.simpleName}")
            }
        }
    }

    fun reset() { opState = OpState() }

    // ── Permission helper ─────────────────────────────────────────────────────
    private suspend fun requestPerm(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            permCb = { granted -> if (cont.isActive) cont.resumeWith(Result.success(granted)) }
            cont.invokeOnCancellation { permCb = null }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(device, PendingIntent.getBroadcast(getApplication(), 0, Intent(ACTION_USB_PERMISSION), flags))
        }
    }

    companion object { const val ACTION_USB_PERMISSION = "com.xfff0.isoflash.USB_PERMISSION" }
}
