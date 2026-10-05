import os
import shutil
import tempfile
import urllib.request
import urllib.error
import zipfile
import json
from pathlib import Path


class DriverManager:
    """Tự tải engine ADB/Platform-Tools và libimobiledevice khi Apple Seed thiếu."""

    ANDROID_URL = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
    IOS_RELEASE_API = "https://api.github.com/repos/jrjr/libimobiledevice-windows/releases/latest"

    def __init__(self, base):
        self.base = Path(base)
        self.platform_tools = self.base / "platform-tools"
        self.ios_tools = self.base / "ios-tools"
        self.platform_tools.mkdir(parents=True, exist_ok=True)
        self.ios_tools.mkdir(parents=True, exist_ok=True)

    def _download(self, url, dest):
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "AppleSeed-Android-Service-Center/1.0"}
        )
        with urllib.request.urlopen(req, timeout=90) as r, open(dest, "wb") as f:
            while True:
                chunk = r.read(1024 * 1024)
                if not chunk:
                    break
                f.write(chunk)

    def _extract_zip(self, archive, target):
        with zipfile.ZipFile(archive, "r") as z:
            z.extractall(target)

    def _find_file(self, root, filename):
        for p in Path(root).rglob(filename):
            if p.is_file():
                return p
        return None

    def ensure_android(self):
        adb = self.platform_tools / ("adb.exe" if os.name == "nt" else "adb")
        if adb.exists():
            return True, "Android ADB đã có sẵn."

        tmp = Path(tempfile.gettempdir()) / "AppleSeed_platform_tools.zip"
        try:
            self._download(self.ANDROID_URL, tmp)
            self._extract_zip(tmp, self.base)
            # Google ZIP thường tạo platform-tools/ trực tiếp.
            adb = self.platform_tools / ("adb.exe" if os.name == "nt" else "adb")
            if adb.exists():
                return True, "Đã tự tải Android SDK Platform-Tools."
            found = self._find_file(self.base, "adb.exe" if os.name == "nt" else "adb")
            if found:
                self.platform_tools.mkdir(parents=True, exist_ok=True)
                for p in found.parent.iterdir():
                    if p.is_file():
                        shutil.copy2(p, self.platform_tools / p.name)
                return True, "Đã tự nạp Android ADB."
            return False, "Tải Platform-Tools xong nhưng không tìm thấy adb."
        except Exception as e:
            return False, "Android driver/ADB: " + str(e)
        finally:
            try:
                tmp.unlink(missing_ok=True)
            except Exception:
                pass

    def _latest_ios_asset(self):
        req = urllib.request.Request(
            self.IOS_RELEASE_API,
            headers={
                "User-Agent": "AppleSeed-Android-Service-Center/1.0",
                "Accept": "application/vnd.github+json",
            }
        )
        with urllib.request.urlopen(req, timeout=30) as r:
            data = json.loads(r.read().decode("utf-8"))
        assets = data.get("assets", [])
        # Ưu tiên Windows ZIP; tránh tải source tarball.
        for a in assets:
            name = str(a.get("name", "")).lower()
            if name.endswith(".zip") and ("win" in name or "windows" in name or "x64" in name):
                return a.get("browser_download_url"), a.get("name")
        for a in assets:
            name = str(a.get("name", "")).lower()
            if name.endswith(".zip"):
                return a.get("browser_download_url"), a.get("name")
        return None, None

    def ensure_ios(self):
        required = ["idevice_id.exe", "ideviceinfo.exe", "irecovery.exe"]
        if all((self.ios_tools / x).exists() for x in required):
            return True, "iOS engine đã có sẵn."

        tmp = Path(tempfile.gettempdir()) / "AppleSeed_libimobiledevice.zip"
        try:
            url, name = self._latest_ios_asset()
            if not url:
                return False, "Không tìm thấy gói Windows ZIP của libimobiledevice."
            self._download(url, tmp)
            self._extract_zip(tmp, self.ios_tools)
            # Asset có thể chứa một thư mục cấp ngoài.
            for req in required:
                dst = self.ios_tools / req
                if not dst.exists():
                    found = self._find_file(self.ios_tools, req)
                    if found:
                        shutil.copy2(found, dst)
            if all((self.ios_tools / x).exists() for x in required):
                return True, "Đã tự tải/nạp libimobiledevice Windows: " + str(name)
            return False, "Đã tải iOS engine nhưng thiếu CLI cần thiết."
        except Exception as e:
            # Fallback: bộ Windows x64 công khai có sẵn các EXE/DLL cần thiết.
            try:
                fallback = "https://github.com/mass1ve-err0r/libimobiledevice-win64/archive/refs/heads/master.zip"
                self._download(fallback, tmp)
                self._extract_zip(tmp, self.ios_tools)
                for req in required:
                    dst = self.ios_tools / req
                    if not dst.exists():
                        found = self._find_file(self.ios_tools, req)
                        if found:
                            shutil.copy2(found, dst)
                if all((self.ios_tools / x).exists() for x in required):
                    return True, "Đã tự nạp bộ iOS Windows x64 dự phòng."
            except Exception:
                pass
            return False, "iOS driver/engine: " + str(e)
        finally:
            try:
                tmp.unlink(missing_ok=True)
            except Exception:
                pass

    def ensure_all(self):
        a_ok, a_msg = self.ensure_android()
        i_ok, i_msg = self.ensure_ios()
        return a_ok, a_msg, i_ok, i_msg
