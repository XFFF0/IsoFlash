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

enum class BurnPhase { IDLE, AWAITING_PERMISSION, WRITING, DONE, FAILED }

data class BurnUiState(
    val phase: BurnPhase = BurnPhase.IDLE,
    val message: String = "",
    val progress: Float = 0f,
    val speedMbPerSec: Double = 0.0,
    val writtenMb: Double = 0.0,
    val totalMb: Double = 0.0
)

class BurnViewModel(application: Application) : AndroidViewModel(application) {

    var drives by mutableStateOf<List<UsbScsiDevice>>(emptyList()); private set
    var selectedDrive by mutableStateOf<UsbScsiDevice?>(null); private set
    var selectedIsoUri by mutableStateOf<Uri?>(null); private set
    var selectedIsoName by mutableStateOf<String?>(null); private set
    var selectedIsoSizeBytes by mutableStateOf(0L); private set
    var uiState by mutableStateOf(BurnUiState()); private set

    private val usbManager get() =
        getApplication<Application>().getSystemService(Context.USB_SERVICE) as UsbManager

    private var pendingPermCb: ((Boolean) -> Unit)? = null

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            pendingPermCb?.invoke(granted); pendingPermCb = null
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

    fun refreshDrives() {
        drives = UsbScsiDevice.findDevices(getApplication())
        if (selectedDrive != null && drives.none { it.usbDevice.deviceId == selectedDrive!!.usbDevice.deviceId })
            selectedDrive = null
    }

    fun selectDrive(d: UsbScsiDevice) {
        selectedDrive = d
        if (uiState.phase != BurnPhase.WRITING) uiState = BurnUiState()
    }

    fun selectIso(uri: Uri) {
        val ctx = getApplication<Application>()
        ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                selectedIsoName = if (ni >= 0) cursor.getString(ni) else "image.iso"
                selectedIsoSizeBytes = if (si >= 0) cursor.getLong(si) else 0L
            }
        }
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        selectedIsoUri = uri
        if (uiState.phase != BurnPhase.WRITING) uiState = BurnUiState()
    }

    private suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            pendingPermCb = { granted -> if (cont.isActive) cont.resumeWith(Result.success(granted)) }
            cont.invokeOnCancellation { pendingPermCb = null }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(device, PendingIntent.getBroadcast(getApplication(), 0, Intent(ACTION_USB_PERMISSION), flags))
        }
    }

    fun startBurn() {
        val drive = selectedDrive ?: return
        val uri = selectedIsoUri ?: return
        val ctx = getApplication<Application>()

        viewModelScope.launch {
            uiState = BurnUiState(BurnPhase.AWAITING_PERMISSION, "في انتظار إذن الوصول للجهاز…")
            if (!requestPermission(drive.usbDevice)) {
                uiState = BurnUiState(BurnPhase.FAILED, "تم رفض إذن الوصول إلى جهاز USB.")
                return@launch
            }
            uiState = BurnUiState(BurnPhase.WRITING, "جاري التهيئة…")

            val totalBytes = selectedIsoSizeBytes.takeIf { it > 0 }
                ?: runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    drive.init()
                    try {
                        val chunkBytes = UsbScsiDevice.BLOCK_SIZE * 128
                        val buf = ByteArray(chunkBytes)
                        val stream = ctx.contentResolver.openInputStream(uri) ?: error("Cannot open ISO")
                        stream.use { input ->
                            var lba = 0L; var written = 0L
                            val t0 = System.currentTimeMillis(); var lastUi = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                val aligned = ((read + UsbScsiDevice.BLOCK_SIZE - 1) / UsbScsiDevice.BLOCK_SIZE) * UsbScsiDevice.BLOCK_SIZE
                                val chunk = if (aligned == buf.size) buf else ByteArray(aligned).also { System.arraycopy(buf, 0, it, 0, read) }
                                drive.writeBlocks(lba, chunk)
                                lba += aligned / UsbScsiDevice.BLOCK_SIZE; written += read
                                val now = System.currentTimeMillis()
                                if (now - lastUi > 150) {
                                    lastUi = now
                                    val sec = (now - t0) / 1000.0
                                    val wMb = written / 1_048_576.0
                                    val speed = if (sec > 0) wMb / sec else 0.0
                                    val tMb = if (totalBytes > 0) totalBytes / 1_048_576.0 else wMb
                                    val prog = if (totalBytes > 0) (written.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                                    withContext(Dispatchers.Main) {
                                        uiState = BurnUiState(BurnPhase.WRITING, "جاري الحرق…", prog, speed, wMb, tMb)
                                    }
                                }
                            }
                            written
                        }
                    } finally { drive.close() }
                }
            }

            result
                .onSuccess { uiState = BurnUiState(BurnPhase.DONE, "تم الحرق بنجاح ✓  يمكنك إخراج الجهاز الآن.", 1f) }
                .onFailure { uiState = BurnUiState(BurnPhase.FAILED, "فشل: ${it.message ?: it::class.simpleName}") }
        }
    }

    fun reset() { uiState = BurnUiState() }

    companion object { const val ACTION_USB_PERMISSION = "com.xfff0.isoflash.USB_PERMISSION" }
}
