"""
最小 FastAPI 示例(ASGI)— 用于验证 K3s 平台 Python 应用发布的 uvicorn 路径。

- ASGI 入口变量为 `app`(平台 appModule = "app:app")
- 平台部署时由 uvicorn 启动:uvicorn app:app --host 0.0.0.0 --port 8000
- 本地调试:python app.py(默认 8000 端口)
"""
import os
import socket

from fastapi import FastAPI

app = FastAPI(title="python-fastapi-demo")

# 演示环境变量读取(未注入时回退默认值,不影响启动)
APP_VERSION = os.environ.get("APP_VERSION", "dev")
GREETING = os.environ.get("GREETING", "Hello from python-fastapi-demo on K3s")


@app.get("/")
def index():
    return {
        "message": GREETING,
        "host": socket.gethostname(),
        "version": APP_VERSION,
    }


@app.get("/health")
def health():
    # 简单存活探针端点(预览环境本期不挂探针,此处供 curl 验证用)
    return {"status": "UP"}


if __name__ == "__main__":
    # 仅本地调试使用;平台部署走 uvicorn(app:app),不会执行到这里。
    import uvicorn

    port = int(os.environ.get("PORT", "8000"))
    uvicorn.run(app, host="0.0.0.0", port=port)
