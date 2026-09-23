package vn.appleseed.ims

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

private val VirusBg = Color(0xFF05070A)
private val VirusPanel = Color(0xFF0D1218)
private val VirusText = Color(0xFFF5F7FA)
private val VirusMuted = Color(0xFF9AA7B5)
private val VirusCyan = Color(0xFF46E6FF)
private val VirusGood = Color(0xFF42E6A4)
private val VirusWarn = Color(0xFFFFC857)
private val VirusDanger = Color(0xFFFF667A)

private data class ScanState(
    val running: Boolean,
    val currentPath: String,
    val files: Long,
    val folders: Long,
    val infected: Int,
    val deleted: Int,
    val result: String
)

class VirusScannerActivity : ComponentActivity() {
    private var scanStarted = false
    private var state by mutableStateOf(
        ScanState(false, "Chưa bắt đầu.", 0, 0, 0, 0, "Sẵn sàng quét toàn bộ tệp dùng chung trên điện thoại.")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VirusScannerScreen() }
    }

    override fun onResume() {
        super.onResume()
        if (Environment.isExternalStorageManager() && !scanStarted) startScan()
    }

    @Composable
    private fun VirusScannerScreen() {
        LaunchedEffect(Unit) {
            if (Environment.isExternalStorageManager() && !scanStarted) startScan()
        }

        val hasAccess = Environment.isExternalStorageManager()
        MaterialTheme(colorScheme = darkColorScheme(primary = VirusCyan, background = VirusBg, surface = VirusPanel, onSurface = VirusText, error = VirusDanger)) {
            Column(Modifier.fillMaxSize().background(VirusBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("APPLE SEED • QUÉT VIRUS", color = VirusText, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text("🦠 TỰ ĐỘNG DÒ TỆP • KHÔNG CẦN WIRELESS ADB", color = VirusCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                if (!hasAccess) {
                    Card(colors = CardDefaults.cardColors(containerColor = VirusPanel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("CẦN QUYỀN TRUY CẬP TẤT CẢ TỆP", color = VirusWarn, fontWeight = FontWeight.Black)
                            Text("Để antivirus tự dò tệp trên bộ nhớ dùng chung, Android yêu cầu quyền 'Cho phép quản lý tất cả tệp'.", color = VirusMuted, fontSize = 11.sp)
                            Button(onClick = { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = android.net.Uri.parse("package:$packageName") }) }, modifier = Modifier.fillMaxWidth()) {
                                Text("CẤP QUYỀN → QUÉT TỰ ĐỘNG")
                            }
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = VirusPanel), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(if (state.running) "🔎 ĐANG DÒ TỆP..." else "KẾT QUẢ QUÉT", color = if (state.running) VirusCyan else resultColor(state.result), fontWeight = FontWeight.Black)
                        Text(state.currentPath, color = VirusText, fontSize = 10.sp)
                        Text("Tệp: ${state.files}   •   Thư mục: ${state.folders}", color = VirusMuted, fontSize = 10.sp)
                        Text("Phát hiện: ${state.infected}   •   Đã xóa: ${state.deleted}", color = if (state.infected > 0) VirusDanger else VirusMuted, fontSize = 10.sp)
                        Text(state.result, color = resultColor(state.result), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Button(onClick = { startScan() }, enabled = hasAccess && !state.running, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.running) "ĐANG QUÉT..." else "🦠 QUÉT LẠI TOÀN BỘ TỆP")
                }
                OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("ĐÓNG") }
            }
        }
    }

    private fun resultColor(text: String): Color = when {
        text.startsWith("CÓ VIRUS") || text.startsWith("QUÉT THẤT BẠI") -> VirusDanger
        text.startsWith("ĐANG") -> VirusCyan
        else -> VirusGood
    }

    private fun startScan() {
        if (state.running || !Environment.isExternalStorageManager()) return
        scanStarted = true
        state = ScanState(true, "/storage/emulated/0", 0, 0, 0, 0, "ĐANG DÒ TỆP — bắt đầu quét...")
        Thread {
            val stats = mutableListOf(0L, 0L, 0, 0)
            var lastUi = 0L
            try {
                val root = Environment.getExternalStorageDirectory()
                scanDirectory(root) { path, files, folders, infected, deleted ->
                    stats[0] = files
                    stats[1] = folders
                    stats[2] = infected
                    stats[3] = deleted
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi >= 150 || infected > 0) {
                        lastUi = now
                        runOnUiThread {
                            state = ScanState(true, path, files, folders, infected, deleted, "ĐANG DÒ TỆP — đang kiểm tra file hiện tại...")
                        }
                    }
                }
                runOnUiThread {
                    state = ScanState(false, "Hoàn tất: /storage/emulated/0", stats[0], stats[1], stats[2], stats[3], if (stats[2] == 0) {
                        "KHÔNG PHÁT HIỆN VIRUS THEO BỘ CHỮ KÝ HIỆN CÓ."
                    } else {
                        "CÓ VIRUS — đã phát hiện ${stats[2]} tệp khớp chữ ký và đã xử lý ${stats[3]} tệp."
                    })
                    scanStarted = false
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    state = ScanState(false, "Dừng tại: ${state.currentPath}", stats[0], stats[1], stats[2], stats[3], "QUÉT THẤT BẠI: ${t.message ?: "Không xác định được lỗi."}")
                    scanStarted = false
                }
            }
        }.start()
    }

    private fun scanDirectory(root: File, onProgress: (String, Long, Long, Int, Int) -> Unit) {
        var files = 0L
        var folders = 0L
        var infected = 0
        var deleted = 0

        fun visit(dir: File) {
            if (!dir.exists() || !dir.isDirectory) return
            if (isBlockedDirectory(dir)) return
            folders++
            onProgress(dir.absolutePath, files, folders, infected, deleted)
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    visit(child)
                    continue
                }
                if (!child.isFile || !child.canRead()) continue
                files++
                val verdict = inspectFile(child)
                if (verdict != null) {
                    infected++
                    val removed = runCatching { child.delete() }.getOrDefault(false)
                    if (removed) deleted++
                }
                onProgress(child.absolutePath, files, folders, infected, deleted)
            }
        }
        visit(root)
    }

    private fun isBlockedDirectory(file: File): Boolean {
        val p = file.absolutePath.replace('\\', '/')
        return p == "/storage/emulated/0/Android/data" ||
            p.startsWith("/storage/emulated/0/Android/data/") ||
            p == "/storage/emulated/0/Android/obb" ||
            p.startsWith("/storage/emulated/0/Android/obb/")
    }

    private fun inspectFile(file: File): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".toByteArray(Charsets.US_ASCII)
        var eicarMatched = false
        val window = ByteArray(eicar.size)
        var windowSize = 0
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
                if (!eicarMatched) {
                    for (i in 0 until n) {
                        if (windowSize < window.size) {
                            window[windowSize++] = buffer[i]
                        } else {
                            System.arraycopy(window, 1, window, 0, window.size - 1)
                            window[window.size - 1] = buffer[i]
                        }
                        if (windowSize == window.size && window.contentEquals(eicar)) {
                            eicarMatched = true
                            break
                        }
                    }
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return when {
            hash in KNOWN_MALWARE_HASHES -> "KNOWN_SIGNATURE:$hash"
            eicarMatched -> "EICAR_TEST_SIGNATURE"
            else -> null
        }
    }

    companion object {
        private val KNOWN_MALWARE_HASHES = setOf(
            "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f"
        )
    }
}
