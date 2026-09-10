from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from brain import analyze, confirm_case

HOST = "127.0.0.1"
PORT = 8765
ROOT = Path(__file__).resolve().parent

HTML = """<!doctype html>
<html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Apple Seed Brain</title>
<style>
body{margin:0;background:#080b0f;color:#edf2f7;font:15px system-ui,sans-serif}main{max-width:1000px;margin:30px auto;padding:0 18px}h1{margin-bottom:4px}small{color:#8b98a6}.grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.card{background:#121820;border:1px solid #26303b;border-radius:14px;padding:16px}label{display:block;margin:10px 0 5px;color:#9eabb8}input,textarea{box-sizing:border-box;width:100%;background:#0b1015;color:#fff;border:1px solid #34414e;border-radius:8px;padding:10px}textarea{min-height:90px;resize:vertical}button{margin-top:12px;border:0;border-radius:9px;padding:11px 16px;background:#e8eef5;color:#081018;font-weight:700;cursor:pointer}.result{white-space:pre-wrap;line-height:1.5}.hyp{border-left:3px solid #49e69b;padding:10px;margin:10px 0;background:#0c1217}.warn{color:#ffc857}@media(max-width:750px){.grid{grid-template-columns:1fr}}
</style></head>
<body><main><h1>APPLE SEED BRAIN</h1><small>LOCAL v1 · không API · dữ liệu lưu trên máy</small>
<div class="grid"><section class="card"><h2>Chẩn đoán</h2>
<label>Model</label><input id="model" placeholder="iPhone 14 Pro Max">
<label>Panic / log</label><textarea id="panic" placeholder="Dán panic log..."></textarea>
<label>Boot current</label><input id="boot" placeholder="120mA → 80mA → reset">
<label>Triệu chứng</label><textarea id="symptoms"></textarea>
<label>Đo đạc</label><textarea id="measurements" placeholder="VBAT..., SDA..., SCL..."></textarea>
<label>Đã thay / đã thử</label><textarea id="replaced"></textarea>
<button onclick="analyzeCase()">PHÂN TÍCH OFFLINE</button></section>
<section class="card"><h2>Kết quả</h2><div id="result" class="result"><span class="warn">Chưa có dữ liệu.</span></div></section></div>
<section class="card" style="margin-top:14px"><h2>Ghi nhận ca đã xác nhận</h2>
<div class="grid"><div><label>Kết luận thực tế</label><textarea id="diagnosis"></textarea></div><div><label>Kết quả sửa</label><textarea id="repairResult"></textarea></div></div>
<button onclick="confirmCase()">LƯU CA ĐÃ XÁC NHẬN</button></section></main>
<script>
async function post(path,data){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});return r.json()}
function input(){return {model:model.value,panic:panic.value,boot_current:boot.value,symptoms:symptoms.value,measurements:measurements.value,replaced:replaced.value}}
async function analyzeCase(){result.textContent='Đang phân tích...';const d=await post('/api/analyze',input());let s=`ENGINE: ${d.engine}\nDEPENDENCY: ${d.online_dependency?'ONLINE':'OFFLINE'}\nEVIDENCE: ${d.decision.evidence_quality}\n\n`;for(const h of d.hypotheses)s+=`[${h.confidence}%] ${h.diagnosis}\nEvidence: ${h.evidence.join(', ')||'none'}\nNext: ${h.next_step}\n\n`;s+='MATCHES:\n'+d.matched_records.map(x=>`${x.source} | ${x.title} | score=${x.score}`).join('\n');result.textContent=s}
async function confirmCase(){const d=await post('/api/confirm',{...input(),diagnosis:diagnosis.value,result:repairResult.value});alert('Đã lưu ca: '+d.title)}
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def _send(self, status: int, content_type: str, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if urlparse(self.path).path == "/":
            self._send(200, "text/html; charset=utf-8", HTML.encode("utf-8"))
        else:
            self._send(404, "text/plain; charset=utf-8", b"Not found")

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        try:
            payload = json.loads(self.rfile.read(length) or b"{}")
            path = urlparse(self.path).path
            if path == "/api/analyze":
                result = analyze(payload)
            elif path == "/api/confirm":
                result = confirm_case(payload)
            else:
                self._send(404, "application/json", b'{"error":"Not found"}')
                return
            self._send(200, "application/json; charset=utf-8", json.dumps(result, ensure_ascii=False).encode("utf-8"))
        except Exception as exc:
            self._send(400, "application/json; charset=utf-8", json.dumps({"error": str(exc)}).encode("utf-8"))

    def log_message(self, fmt: str, *args: object) -> None:
        print(fmt % args)


if __name__ == "__main__":
    print(f"Apple Seed Brain running at http://{HOST}:{PORT}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
