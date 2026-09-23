# Apple Seed Android Service Center • PySide6

Bản giao diện mới, tập trung vào thao tác sửa máy thực tế:
- ADB tích hợp trong platform-tools
- Tự nhận thiết bị và hiển thị Model / Android / SoC
- Chẩn đoán battery, getprop, dumpsys, sensor, camera, logcat
- Screenshot, reboot, recovery, bootloader
- Quản lý APK và file qua ADB
- Console ADB
- VoLTE / IMS
- Native CarrierConfig 1-Click + fallback VoLTE flags
- Log riêng, chạy tác vụ nền để giao diện không bị treo

Chạy run_tool.bat. Nếu thiếu PySide6, launcher tự cài từ requirements.txt.

Lưu ý: VoLTE/IMS còn phụ thuộc ROM/ColorOS, SIM, provisioning, carrier và modem.
