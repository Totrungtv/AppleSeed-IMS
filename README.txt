APPLE SEED ANDROID SERVICE CENTER - CLEAN 1-CLICK VoLTE

File chính:
- AppleSeed_Android_Service_Center.py
- run_tool.bat
- apps/AppleSeed_VoLTE.apk
- assets/hbg_volte_fixer.dex

VoLTE 1-CLICK:
1. Kết nối Android bằng ADB/USB Debugging.
2. Mở tool bằng run_tool.bat.
3. Vào VoLTE / IMS.
4. Chọn KÍCH HOẠT VoLTE TỰ ĐỘNG 1-CLICK.

Tool thử native CarrierConfig runner, sau đó fallback các cờ VoLTE phổ biến và đọc lại CarrierConfig/IMS.
Không thay đổi IMEI, SIM lock, carrier lock, firmware modem hoặc bootloader.

Lưu ý: bật CarrierConfig không đảm bảo IMS Registered; còn phụ thuộc ROM/ColorOS, SIM, nhà mạng, provisioning và modem.
