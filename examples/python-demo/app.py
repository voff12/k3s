"""
最小 Flask 示例 — 用于验证 K3s 平台的 Python 应用发布 / 合并预览部署。

- WSGI 入口变量为 `app`(平台默认 appModule = "app:app")
- 平台部署时由 gunicorn 启动:gunicorn -b 0.0.0.0:8000 app:app
- 本地调试:python app.py(默认 8000 端口)
"""
import os
import socket

from flask import Flask, jsonify

app = Flask(__name__)

# 演示环境变量读取(也用于验证设计文档中"预览 Pod env 注入"这一后续增强项:
# 未注入时回退默认值,不影响启动)
APP_VERSION = os.environ.get("APP_VERSION", "dev")
GREETING = os.environ.get("GREETING", "Hello from python-demo on K3s")


@app.get("/")
def index():
    return jsonify(
        message=GREETING,
        host=socket.gethostname(),
        version=APP_VERSION,
    )


@app.get("/health")
def health():
    # 简单存活探针端点(预览环境本期不挂探针,此处供 curl 验证用)
    return jsonify(status="UP"), 200


if __name__ == "__main__":
    # 仅本地调试使用;平台部署走 gunicorn(app:app),不会执行到这里。
    port = int(os.environ.get("PORT", "8000"))
    app.run(host="0.0.0.0", port=port)
