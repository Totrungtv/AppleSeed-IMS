import os
import subprocess
import shutil
from pathlib import Path


class IOSDeviceEngine:
    """Local iOS communication layer for Apple Seed.

    This module intentionally handles device detection and read-only diagnostics.
    It does not implement Activation Lock/Hello/passcode bypass.
    """

    def __init__(self, base):
        self.base = Path(base)
        self.roots = [
            self.base / "ios-tools",
            self.base / "tools" / "ios",
        ]

    def find_cli(self, name):
        exe = name + (".exe" if os.name == "nt" else "")
        for root in self.roots:
            direct = root / exe
            if direct.exists():
                return direct
            if root.exists():
                try:
                    hit = next(root.rglob(exe), None)
                except Exception:
                    hit = None
                if hit and hit.is_file():
                    return hit
        hit = shutil.which(name)
        return Path(hit) if hit else None

    def run(self, name, args=None, timeout=20):
        path = self.find_cli(name)
        if not path:
            raise RuntimeError(f"Không tìm thấy {name}. Apple Seed cần bộ iOS engine.")
        env = os.environ.copy()
        env["PATH"] = str(path.parent) + os.pathsep + env.get("PATH", "")
        p = subprocess.run(
            [str(path)] + list(args or []),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            cwd=str(path.parent),
            env=env,
            timeout=timeout,
            creationflags=(subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0),
        )
        out = ((p.stdout or "") + (p.stderr or "")).strip()
        return p.returncode, out

    def normal_devices(self):
        rc, out = self.run("idevice_id", ["-l"], 15)
        ids = [x.strip() for x in out.splitlines() if x.strip()]
        return ids, out, rc

    def device_info(self, udid=None):
        args = ["-u", udid] if udid else []
        rc, out = self.run("ideviceinfo", args, 20)
        data = {}
        for line in out.splitlines():
            if ":" in line:
                key, value = line.split(":", 1)
                data[key.strip()] = value.strip()
        return data, out, rc

    def recovery_info(self):
        rc, out = self.run("irecovery", ["-q"], 15)
        return out, rc

    def probe(self):
        result = {
            "normal": [],
            "recovery": "",
            "mode": "Not detected",
            "details": "",
        }
        try:
            ids, out, rc = self.normal_devices()
            if rc == 0 and ids:
                result["normal"] = ids
                result["mode"] = "Normal"
                result["details"] = out
                return result
        except Exception as e:
            result["details"] = str(e)

        try:
            out, rc = self.recovery_info()
            if rc == 0 and out.strip():
                result["recovery"] = out
                text = out.lower()
                result["mode"] = "DFU" if ("dfu" in text or "dfu mode" in text) else "Recovery"
                result["details"] = out
        except Exception as e:
            result["details"] = str(e)

        return result
