package vn.appleseed.volte

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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

class MainActivity : ComponentActivity() {
    private fun read(key: String): Boolean =
        runCatching { Settings.Global.getInt(contentResolver, key, 0) == 1 }.getOrDefault(false)

    private fun hasSecureSettingsPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // Không yêu cầu cả 4 key: nhiều ROM/ColorOS chỉ dùng một hoặc hai key chính.
    private fun allEnabled() =
        read(KEY_VOLTE_VT) || read(KEY_ENHANCED_4G)

    private fun write(key: String, value: Boolean): Boolean {
        if (!hasSecureSettingsPermission()) {
            throw SecurityException("WRITE_SECURE_SETTINGS chưa được cấp. Hãy chạy CẤP QUYỀN SYSTEM từ WebADB.")
        }
        return Settings.Global.putInt(contentResolver, key, if (value) 1 else 0)
    }

    private fun setVolte(enabled: Boolean): Boolean {
        if (!hasSecureSettingsPermission()) {
            throw SecurityException("WRITE_SECURE_SETTINGS chưa được cấp. Hãy chạy CẤP QUYỀN SYSTEM từ WebADB.")
        }

        val results = linkedMapOf<String, Boolean>()
        for (key in listOf(KEY_VOLTE_VT, KEY_ENHANCED_4G, KEY_VOLTE, KEY_CARRIER_VT)) {
            results[key] = runCatching {
                Settings.Global.putInt(contentResolver, key, if (enabled) 1 else 0)
            }.getOrDefault(false)
        }

        // Không bắt buộc mọi ROM phải có đủ 4 key. Trên một số ColorOS chỉ
        // volte_vt_enabled/enhanced_4g_mode_enabled tồn tại và có tác dụng.
        val primary = read(KEY_VOLTE_VT)
        val enhanced = read(KEY_ENHANCED_4G)
        return if (enabled) primary || enhanced else !primary && !enhanced
    }
    private fun openVolteSettings() {
        // Samsung/Android không dùng một Activity duy nhất cho mọi phiên bản.
        // Thử màn hình Mobile Network cụ thể trước, sau đó fallback về Settings chuẩn.
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
        setContent {
            var enabled by remember { mutableStateOf(allEnabled()) }
            var secureGranted by remember { mutableStateOf(hasSecureSettingsPermission()) }
            var status by remember {
                mutableStateOf(
                    if (!secureGranted) "CHƯA CÓ QUYỀN GHI CỜ HỆ THỐNG — hãy cấp quyền từ WebADB."
                    else if (enabled) "Cờ VoLTE chính đang BẬT."
                    else "Cờ VoLTE chính đang TẮT."
                )
            }

            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("🍎 Apple Seed VoLTE", style = MaterialTheme.typography.headlineMedium)
                    Text("Hai chức năng riêng: điều khiển cờ và mở Cài đặt mạng để tự thao tác.")

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("CỜ VoLTE (ADB/QUYỀN HỆ THỐNG)", style = MaterialTheme.typography.titleMedium)
                                Text(if (enabled) "CỜ VoLTE CHÍNH ĐANG BẬT" else "CỜ VoLTE CHÍNH ĐANG TẮT")
                            }
                            Switch(
                                enabled = secureGranted,
                                checked = enabled,
                                onCheckedChange = { wantEnabled ->
                                    runCatching {
                                        val accepted = setVolte(wantEnabled)
                                        enabled = allEnabled()
                                        status = when {
                                            accepted && enabled == wantEnabled ->
                                                "Đã ghi và đọc lại cờ VoLTE chính: " + if (wantEnabled) "BẬT." else "TẮT."
                                            else ->
                                                "Đã thử ghi nhưng ROM không xác nhận cờ VoLTE chính. Xem 4 dòng trạng thái bên dưới."
                                        }
                                    }.onFailure { e ->
                                        enabled = allEnabled()
                                        secureGranted = hasSecureSettingsPermission()
                                        status = if (e is SecurityException) {
                                            "Chưa có WRITE_SECURE_SETTINGS. Hãy cấp quyền từ WebADB rồi mở lại app."
                                        } else {
                                            "Không thể đổi cờ: " + (e.message ?: e.javaClass.simpleName)
                                        }
                                    }
                                }
                            )
                        }
                    }

                    Text(status)
                    Text("TRẠNG THÁI QUYỀN & CỜ HỆ THỐNG:", style = MaterialTheme.typography.titleMedium)
                    Text("WRITE_SECURE_SETTINGS = " + if (secureGranted) "GRANTED" else "DENIED")
                    Text("volte_vt_enabled = " + read(KEY_VOLTE_VT))
                    Text("enhanced_4g_mode_enabled = " + read(KEY_ENHANCED_4G))
                    Text("volte_enabled = " + read(KEY_VOLTE))
                    Text("carrier_vt_enabled = " + read(KEY_CARRIER_VT))

                    Button(
                        onClick = {
                            secureGranted = hasSecureSettingsPermission()
                            enabled = allEnabled()
                            status = when {
                                !secureGranted -> "WRITE_SECURE_SETTINGS chưa được cấp. Hãy chạy CẤP QUYỀN SYSTEM từ WebADB."
                                enabled -> "Cờ VoLTE chính đã BẬT."
                                else -> "Đã có quyền SYSTEM nhưng cờ VoLTE chính vẫn TẮT."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("KIỂM TRA CỜ") }

                    Divider()

                    Text("CÀI ĐẶT VoLTE CỦA ANDROID", style = MaterialTheme.typography.titleMedium)
                    Text("Nút này chỉ mở Cài đặt mạng. Ông tự tìm và bật/tắt VoLTE trên máy; APK không tự đổi công tắc hệ thống ở đây.")

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
                        "Lưu ý: ghi được cờ không chứng minh VoLTE hoạt động. IMS thực tế phụ thuộc SIM, nhà mạng, cấu hình nhà mạng, provisioning và modem.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
