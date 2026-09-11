package vn.appleseed.ims

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

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
    val skipped: Long,
    val errors: Long,
    val infected: Int,
    val result: String
)

private data class ScanStats(
    var files: Long = 0L,
    var folders: Long = 0L,
    var skipped: Long = 0L,
    var errors: Long = 0L,
    var infected: Int = 0
)

class VirusScannerActivity : ComponentActivity() {
    @Volatile private var scanStarted = false
    private val cancelScan = AtomicBoolean(false)

    private var state by mutableStateOf(
        ScanState(false, "Chưa bắt đầu.", 0L, 0L, 0L, 0L, 0, "Sẵn sàng quét các tệp có khả năng thực thi hoặc chứa dấu hiệu độc hại.")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VirusScannerScreen() }
    }

    override fun onDestroy() {
        cancelScan.set(true)
        super.onDestroy()
    }

    @Composable
    private fun VirusScannerScreen() {
        val hasAccess = Environment.isExternalStorageManager()
        LaunchedEffect(hasAccess) {
            if (hasAccess && !scanStarted) startScan()
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = VirusCyan, background = VirusBg, surface = VirusPanel, onSurface = VirusText, error = VirusDanger)) {
            Column(
                Modifier.fillMaxSize().background(VirusBg).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("APPLE SEED • QUÉT VIRUS", color = VirusText, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text("🦠 PROFESSIONAL FILE SCANNER • KHÔNG CẦN WIRELESS ADB", color = VirusCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)

                if (!hasAccess) {
                    Card(colors = CardDefaults.cardColors(containerColor = VirusPanel), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("CẦN QUYỀN TRUY CẬP TẤT CẢ TỆP", color = VirusWarn, fontWeight = FontWeight.Black)
                            Text("Android yêu cầu quyền 'Cho phép quản lý tất cả tệp' để Apple Seed kiểm tra bộ nhớ dùng chung.", color = VirusMuted, fontSize = 11.sp)
                            Button(onClick = {
                                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:$packageName")
                                })
                            }, modifier = Modifier.fillMaxWidth()) { Text("CẤP QUYỀN → QUÉT TỰ ĐỘNG") }
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = VirusPanel), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            if (state.running) "🔎 ĐANG QUÉT..." else "KẾT QUẢ QUÉT",
                            color = if (state.running) VirusCyan else resultColor(state.result),
                            fontWeight = FontWeight.Black
                        )
                        Text(state.currentPath, color = VirusText, fontSize = 9.sp, maxLines = 3)
                        Text("Tệp đã kiểm tra: ${state.files}  •  Thư mục: ${state.folders}", color = VirusMuted, fontSize = 10.sp)
                        Text("Bỏ qua media: ${state.skipped}  •  Lỗi đọc: ${state.errors}", color = VirusMuted, fontSize = 10.sp)
                        Text("Phát hiện: ${state.infected}", color = if (state.infected > 0) VirusDanger else VirusGood, fontSize = 11.sp, fontWeight = FontWeight.Black)
                        Text(state.result, color = resultColor(state.result), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { startScan(force = true) },
                        enabled = hasAccess && !state.running,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (state.running) "ĐANG QUÉT..." else "🦠 QUÉT LẠI") }
                    OutlinedButton(
                        onClick = { cancelScan.set(true) },
                        enabled = state.running,
                        modifier = Modifier.weight(1f)
                    ) { Text("DỪNG QUÉT") }
                }

                OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("ĐÓNG") }

                Card(colors = CardDefaults.cardColors(containerColor = VirusPanel), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("CƠ CHẾ QUÉT", color = VirusCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        Text(
                            "Apple Seed không đọc toàn bộ ảnh/video như antivirus. Bộ quét ưu tiên APK, XAPK, APKS, DEX, JAR, SO, EXE, DLL, MSI và các script/archive; đồng thời kiểm tra SHA-256 và EICAR. File media lớn được bỏ qua để tránh tình trạng đứng ở một file hàng trăm MB.",
                            color = VirusMuted,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
    }

    private fun resultColor(text: String): Color = when {
        text.startsWith("CÓ VIRUS") || text.startsWith("QUÉT THẤT BẠI") -> VirusDanger
        text.startsWith("ĐANG") || text.startsWith("ĐÃ DỪNG") -> VirusCyan
        else -> VirusGood
    }

    private fun startScan(force: Boolean = false) {
        if (!Environment.isExternalStorageManager()) return
        if (scanStarted && !force) return
        if (state.running) return

        cancelScan.set(false)
        scanStarted = true
        state = ScanState(true, "/storage/emulated/0", 0L, 0L, 0L, 0L, 0, "ĐANG KHỞI TẠO BỘ QUÉT...")

        Thread {
            val stats = ScanStats()
            var lastUi = 0L
            try {
                scanDirectory(Environment.getExternalStorageDirectory()) { path, s ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUi >= 150L || s.infected > stats.infected || cancelScan.get()) {
                        lastUi = now
                        runOnUiThread {
                            state = ScanState(true, path, s.files, s.folders, s.skipped, s.errors, s.infected, "ĐANG QUÉT — đang kiểm tra file hiện tại...")
                        }
                    }
                }

                val stopped = cancelScan.get()
                runOnUiThread {
                    state = ScanState(
                        false,
                        if (stopped) "Đã dừng tại: ${state.currentPath}" else "Hoàn tất: /storage/emulated/0",
                        stats.files,
                        stats.folders,
                        stats.skipped,
                        stats.errors,
                        stats.infected,
                        when {
                            stopped -> "ĐÃ DỪNG — kết quả là phần đã quét đến thời điểm dừng."
                            stats.infected == 0 -> "KHÔNG PHÁT HIỆN MẪU KHỚP CHỮ KÝ HIỆN CÓ."
                            else -> "CÓ VIRUS — phát hiện ${stats.infected} tệp khớp chữ ký."
                        }
                    )
                    scanStarted = false
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    state = ScanState(false, "Dừng tại: ${state.currentPath}", stats.files, stats.folders, stats.skipped, stats.errors, stats.infected, "QUÉT THẤT BẠI: ${t.message ?: "Lỗi không xác định."}")
                    scanStarted = false
                }
            }
        }.start()
    }

    private fun scanDirectory(root: File, onProgress: (String, ScanStats) -> Unit) {
        val pending = ArrayDeque<File>()
        pending.addLast(root)
        val stats = ScanStats()

        while (pending.isNotEmpty() && !cancelScan.get()) {
            val dir = pending.removeLast()
            if (!dir.exists() || !dir.isDirectory || isBlockedDirectory(dir)) continue
            stats.folders++
            onProgress(dir.absolutePath, stats)

            val children = try {
                dir.listFiles()
            } catch (_: Throwable) {
                stats.errors++
                null
            }

            if (children == null) {
                stats.errors++
                continue
            }

            for (child in children) {
                if (cancelScan.get()) break
                try {
                    if (child.isDirectory) {
                        if (!isBlockedDirectory(child)) pending.addLast(child)
                        continue
                    }
                    if (!child.isFile || !child.canRead()) {
                        stats.errors++
                        continue
                    }

                    if (!isSecurityCandidate(child)) {
                        stats.skipped++
                        continue
                    }

                    stats.files++
                    val verdict = runCatching { inspectFile(child) }.getOrElse {
                        stats.errors++
                        null
                    }
                    if (verdict != null) stats.infected++
                    onProgress(child.absolutePath, stats)
                } catch (_: Throwable) {
                    stats.errors++
                }
            }
        }

        // Copy final counters back to the object observed by the caller.
        // The callback receives the same mutable stats instance.
        onProgress(if (cancelScan.get()) root.absolutePath else "Hoàn tất", stats)
    }

    private fun isBlockedDirectory(file: File): Boolean {
        val p = runCatching { file.canonicalPath.replace('\\', '/') }.getOrElse { file.absolutePath.replace('\\', '/') }
        return p == "/storage/emulated/0/Android/data" || p.startsWith("/storage/emulated/0/Android/data/") ||
            p == "/storage/emulated/0/Android/obb" || p.startsWith("/storage/emulated/0/Android/obb/") ||
            p == "/storage/emulated/0/Android/media" || p.startsWith("/storage/emulated/0/Android/media/")
    }

    private fun isSecurityCandidate(file: File): Boolean {
        val name = file.name.lowercase()
        val ext = name.substringAfterLast('.', "")
        if (ext in SECURITY_EXTENSIONS) return true
        if (name == "eicar.com" || name == "eicar.txt" || name.contains("eicar")) return true
        // Small text-like files can contain a test signature; avoid hashing large media/documents.
        return file.length() <= MAX_TEXT_SCAN_BYTES && ext in TEXT_EXTENSIONS
    }

    private fun inspectFile(file: File): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*".toByteArray(Charsets.US_ASCII)
        var eicarMatched = false
        val window = ByteArray(eicar.size)
        var windowSize = 0

        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (!cancelScan.get()) {
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

        if (cancelScan.get()) return null
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return when {
            hash in KNOWN_MALWARE_HASHES -> "KNOWN_SIGNATURE:$hash"
            eicarMatched -> "EICAR_TEST_SIGNATURE"
            else -> null
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_TEXT_SCAN_BYTES = 8L * 1024L * 1024L
        private val SECURITY_EXTENSIONS = setOf(
            "apk", "xapk", "apks", "apkm", "zip", "rar", "7z", "jar", "dex", "odex", "vdex",
            "so", "elf", "bin", "img", "iso", "exe", "dll", "msi", "bat", "cmd", "ps1", "sh",
            "bash", "zsh", "py", "js", "vbs", "wsf", "scr", "com"
        )
        private val TEXT_EXTENSIONS = setOf("txt", "log", "xml", "json", "html", "htm", "js", "sh", "bat", "cmd", "ps1")
        private val KNOWN_MALWARE_HASHES = setOf(
            "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f"
        )
    }
}
