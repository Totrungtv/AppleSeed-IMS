package vn.appleseed.volte

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import java.io.InputStream
import rikka.shizuku.Shizuku
import org.lsposed.hiddenapibypass.HiddenApiBypass
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val KEY_VOLTE_VT = "volte_vt_enabled"
private const val KEY_ENHANCED_4G = "enhanced_4g_mode_enabled"
private const val KEY_VOLTE = "volte_enabled"
private const val KEY_CARRIER_VT = "carrier_vt_enabled"
private const val SHIZUKU_REQUEST_CODE = 4107
private const val ACTION_FIX_VOLTE = "vn.appleseed.volte.action.FIX_VOLTE"

class MainActivity : ComponentActivity() {
    private var shizukuGrantedState by mutableStateOf(false)
    private var shizukuRunningState by mutableStateOf(false)

    private fun read(key: String): Boolean =
        runCatching { Settings.Global.getInt(contentResolver, key, 0) == 1 }.getOrDefault(false)

    private fun hasSecureSettingsPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun shizukuRunning(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun shizukuGranted(): Boolean =
        shizukuRunning() && runCatching {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    private fun refreshShizukuState() {
        shizukuRunningState = shizukuRunning()
        shizukuGrantedState = shizukuGranted()
    }

    private fun requestShizukuPermission() {
        refreshShizukuState()
        if (!shizukuRunningState) {
            throw IllegalStateException("Shizuku chưa chạy. Hãy mở Shizuku và Start bằng ADB/USB hoặc Wireless ADB.")
        }
        if (!shizukuGrantedState) {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }
    }

    /**
     * Chạy lệnh với identity của Shizuku backend (ADB shell hoặc root).
     * newProcess đã deprecated ở Shizuku 13 và dự kiến bị bỏ ở API 14,
     * nên gọi qua reflection để bản thử không bị lỗi compile.
     */
    private fun runShizukuCommand(vararg args: String): String {
        refreshShizukuState()
        if (!shizukuRunningState) {
            throw IllegalStateException("Shizuku chưa chạy.")
        }
        if (!shizukuGrantedState) {
            throw SecurityException("Apple Seed chưa được cấp quyền Shizuku.")
        }

        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true

        val remote = method.invoke(null, args, null, null)
            ?: throw IllegalStateException("Shizuku không tạo được process.")

        return try {
            val input = remote.javaClass.getMethod("getInputStream").invoke(remote) as InputStream
            val error = remote.javaClass.getMethod("getErrorStream").invoke(remote) as InputStream
            val waitFor = remote.javaClass.getMethod("waitFor")
            waitFor.invoke(remote)
            val outText = input.bufferedReader().use { it.readText() }.trim()
            val errText = error.bufferedReader().use { it.readText() }.trim()
            if (errText.isNotEmpty()) "ERROR: $errText" else outText
        } finally {
            runCatching { remote.javaClass.getMethod("destroy").invoke(remote) }
        }
    }

    private fun writeViaShizuku(key: String, value: Boolean): Boolean {
        val result = runShizukuCommand("settings", "put", "global", key, if (value) "1" else "0")
        if (result.startsWith("ERROR:", ignoreCase = true)) {
            throw IllegalStateException(result)
        }
        return read(key) == value
    }

    private fun allEnabled() =
        read(KEY_VOLTE_VT) || read(KEY_ENHANCED_4G)

    private fun setVolte(enabled: Boolean): Boolean {
        if (hasSecureSettingsPermission()) {
            for (key in listOf(KEY_VOLTE_VT, KEY_ENHANCED_4G, KEY_VOLTE, KEY_CARRIER_VT)) {
                runCatching {
                    Settings.Global.putInt(contentResolver, key, if (enabled) 1 else 0)
                }
            }
        } else {
            refreshShizukuState()
            if (!shizukuGrantedState) {
                throw SecurityException("Chưa có WRITE_SECURE_SETTINGS và Shizuku chưa được cấp quyền.")
            }
            for (key in listOf(KEY_VOLTE_VT, KEY_ENHANCED_4G, KEY_VOLTE, KEY_CARRIER_VT)) {
                writeViaShizuku(key, enabled)
            }
        }

        val primary = read(KEY_VOLTE_VT)
        val enhanced = read(KEY_ENHANCED_4G)
        return if (enabled) primary || enhanced else !primary && !enhanced
    }

    private fun openVolteSettings() {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName("com.android.phone", "com.android.phone.settings.MobileNetworkSettings")
            ),
            Intent().setComponent(
                ComponentName("com.android.phone", "com.android.phone.settings.Settings\$MobileNetworkSettingsActivity")
            ),
            Intent("android.settings.NETWORK_OPERATOR_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )

        for (intent in candidates) {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        }
        throw IllegalStateException("ROM không cung cấp màn hình Mobile Network/VoLTE")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        HiddenApiBypass.addHiddenApiExemptions("L", "I")

        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                shizukuGrantedState =
                    grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                shizukuRunningState = shizukuRunning()
            }
        }

        refreshShizukuState()

        if (intent?.action == ACTION_FIX_VOLTE) {
            runCatching {
                CarrierConfigBridge.applyVoLTE(this)
            }
        }

        setContent {
            var enabled by remember { mutableStateOf(allEnabled()) }
            var secureGranted by remember { mutableStateOf(hasSecureSettingsPermission()) }
            var status by remember {
                mutableStateOf("Đang kiểm tra quyền…")
            }

            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("🍎 Apple Seed VoLTE", style = MaterialTheme.typography.headlineMedium)
                    Text("Hỗ trợ CarrierConfig thật qua Shizuku + IMS reset, kèm cờ hệ thống.")

                    Button(
                        onClick = {
                            runCatching {
                                status = CarrierConfigBridge.applyVoLTE(this@MainActivity)
                                enabled = true
                            }.onFailure { e ->
                                refreshShizukuState()
                                status = "✕ CarrierConfig: " + (e.message ?: e.javaClass.simpleName)
                            }
                        },
                        enabled = shizukuGrantedState,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("⚡ FIX VOLTE + CARRIERCONFIG + IMS") }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("SHIZUKU BACKEND", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    shizukuGrantedState -> "✓ Shizuku đang chạy và Apple Seed đã được cấp quyền."
                                    shizukuRunningState -> "Shizuku đang chạy nhưng Apple Seed chưa được cấp quyền."
                                    else -> "Shizuku chưa chạy."
                                }
                            )
                            Button(
                                onClick = {
                                    runCatching {
                                        requestShizukuPermission()
                                        status = "Đã gửi yêu cầu quyền Shizuku. Hãy bấm Cho phép nếu hộp thoại xuất hiện."
                                    }.onFailure {
                                        status = it.message ?: "Không thể yêu cầu Shizuku."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (shizukuRunningState) "🔐 CẤP QUYỀN SHIZUKU" else "▶ KIỂM TRA SHIZUKU") }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("CỜ VoLTE", style = MaterialTheme.typography.titleMedium)
                                Text(if (enabled) "CỜ VoLTE CHÍNH ĐANG BẬT" else "CỜ VoLTE CHÍNH ĐANG TẮT")
                                Text(
                                    if (secureGranted) "Nguồn: WRITE_SECURE_SETTINGS"
                                    else if (shizukuGrantedState) "Nguồn: Shizuku / ADB shell"
                                    else "Chưa có backend ghi"
                                )
                            }
                            Switch(
                                enabled = secureGranted || shizukuGrantedState,
                                checked = enabled,
                                onCheckedChange = { wantEnabled ->
                                    runCatching {
                                        val accepted = setVolte(wantEnabled)
                                        enabled = allEnabled()
                                        secureGranted = hasSecureSettingsPermission()
                                        status = if (accepted && enabled == wantEnabled) {
                                            "✓ Đã ghi và đọc lại cờ VoLTE: " + if (wantEnabled) "BẬT." else "TẮT."
                                        } else {
                                            "⚠ Đã ghi thử nhưng ROM không xác nhận cờ chính."
                                        }
                                    }.onFailure { e ->
                                        enabled = allEnabled()
                                        secureGranted = hasSecureSettingsPermission()
                                        refreshShizukuState()
                                        status = e.message ?: e.javaClass.simpleName
                                    }
                                }
                            )
                        }
                    }

                    Text(status)
                    Text("TRẠNG THÁI:", style = MaterialTheme.typography.titleMedium)
                    Text("WRITE_SECURE_SETTINGS = " + if (secureGranted) "GRANTED" else "DENIED")
                    Text("Shizuku = " + when {
                        shizukuGrantedState -> "GRANTED"
                        shizukuRunningState -> "RUNNING / CHƯA GRANT"
                        else -> "OFF"
                    })
                    Text("volte_vt_enabled = " + read(KEY_VOLTE_VT))
                    Text("enhanced_4g_mode_enabled = " + read(KEY_ENHANCED_4G))
                    Text("volte_enabled = " + read(KEY_VOLTE))
                    Text("carrier_vt_enabled = " + read(KEY_CARRIER_VT))

                    Button(
                        onClick = {
                            refreshShizukuState()
                            secureGranted = hasSecureSettingsPermission()
                            enabled = allEnabled()
                            status = "Đã làm mới trạng thái quyền và cờ."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🔄 KIỂM TRA LẠI") }

                    Divider()

                    Text("CÀI ĐẶT VoLTE CỦA ANDROID", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = {
                            runCatching {
                                openVolteSettings()
                                status = "Đã mở màn hình mạng/VoLTE của máy."
                            }.onFailure { e ->
                                status = "Không mở được Cài đặt VoLTE: " + (e.message ?: e.javaClass.simpleName)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📶 MỞ CÀI ĐẶT VoLTE") }

                    Text(
                        "Lưu ý: bật được cờ không đồng nghĩa IMS đã hoạt động. SIM, nhà mạng, provisioning và modem vẫn quyết định VoLTE thực tế.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
