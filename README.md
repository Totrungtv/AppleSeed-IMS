# Apple Seed IMS

Android IMS / VoLTE diagnostic project for Apple Seed.

## V1
- Device / model / Android / SDK
- Active SIM count
- Carrier and MCC/MNC
- Data and voice network type
- Shizuku connection/permission status
- Read-only diagnostics; no CarrierConfig/IMS/VoLTE modification

## Roadmap
- V2: IMS registration + carrier diagnostics (read-only)
- V3: Shizuku + CarrierConfig override with verification and rollback
- V4: Apple Seed technical AI assistant

## Open in Android Studio
1. Clone/download this repository.
2. Open the project root in Android Studio.
3. Use JDK 17 for Gradle if prompted.
4. Install Android SDK Platform 37.
5. Sync Gradle and run on a real Android device.

## Important
This project is currently a V1 test foundation. Actual CarrierConfig/IMS override must be validated on compatible devices and carriers before release.

Pixel IMS is treated as a behavioral/reference project. This repository does not copy its branding/assets/source code in V1. If GPL-3.0 code is incorporated later, the applicable license obligations must be followed.

## Automatic Phone Diagnosis

Apple Seed now includes a read-only **Phone Diagnostic Engine** in the Windows Android Service Center:
- One-click automatic diagnosis from USB/ADB.
- Checks device identity, battery/temperature, storage, RAM, display service, sensors, camera service, audio service, USB and Package Manager.
- Produces automatic OK / WARN / FAIL results and conservative fault conclusions.
- Does **not** use LOGCAT as the diagnostic source.
- Hardware faults that cannot be established over USB (shorted power rails, dead PMIC/IC, broken traces, physical NAND/UFS faults, etc.) are explicitly reported as requiring bench measurement.
