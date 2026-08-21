#!/usr/bin/env python3
"""极简本地 embedding 服务（片3 · base.knowledge 依赖，零框架依赖）。

收自 deepseek-harness demo（本机依赖启动.md 既定：Phase A 直接用 demo 的极简服务），
模型与接口不变。启动：

    uv run --with fastembed python scripts/embedding_server.py
    # 或已装好依赖：python3 scripts/embedding_server.py

接口：GET /health -> {"ok":true}
      POST /embed {"texts":["..."]} -> {"vectors":[[...]]}
模型：BAAI/bge-small-zh-v1.5（中文优化，512 维，ONNX 运行时，不装 torch）
"""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

from fastembed import TextEmbedding

PORT = int(os.environ.get("EMBED_PORT", "9091"))
_model = None


def model():
    global _model
    if _model is None:
        _model = TextEmbedding("BAAI/bge-small-zh-v1.5")
    return _model


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"ok":true}')
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if self.path != "/embed":
            self.send_response(404)
            self.end_headers()
            return
        try:
            n = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(n) or b"{}")
            texts = [t for t in body.get("texts", []) if t and t.strip()]
            vectors = [v.tolist() for v in model().embed(texts)] if texts else []
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"vectors": vectors}).encode())
        except Exception as e:
            self.send_response(500)
            self.end_headers()
            self.wfile.write(json.dumps({"error": str(e)}).encode())

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"embedding server on :{PORT} (model=BAAI/bge-small-zh-v1.5, dim=512)")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
