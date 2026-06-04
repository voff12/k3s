# python-fastapi-demo — FastAPI(ASGI / uvicorn)验证用最小示例

与 `../python-demo`(Flask / WSGI / gunicorn)平行的 **ASGI** 版本,用于验证 K3s 平台 Python 发布的 **uvicorn 路径**。

```
python-fastapi-demo/
├── app.py            # FastAPI 应用, ASGI 入口变量为 app(appModule = app:app)
├── requirements.txt  # fastapi + uvicorn
├── .dockerignore
└── .gitignore
```

> 同样**故意不含 Dockerfile**,验证平台「无 Dockerfile → 按 runtime=python 自动生成」路径。

---

## 1. 本地快速跑通

```bash
cd examples/python-fastapi-demo
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# 方式 A: 直接跑(调试)
python app.py
# 方式 B: 模拟平台启动方式(ASGI)
uvicorn app:app --host 0.0.0.0 --port 8000

curl http://localhost:8000/         # {"message":"...","host":"...","version":"dev"}
curl http://localhost:8000/health   # {"status":"UP"}
```

---

## 2. 在平台上发布的字段

与 Flask 版基本一致,**仅 pythonServer 不同**:

| 字段 | 值 |
|------|-----|
| 运行时 runtime | **Python** |
| 镜像名称 | `python-fastapi-demo` |
| Python 版本 | `3.11` |
| 应用端口 appPort | `8000` |
| 服务类型 pythonServer | **`uvicorn`**(ASGI) |
| 入口模块 appModule | `app:app` |
| requirements 路径 | `requirements.txt` |
| 需要编译依赖 buildDeps | 关 |

平台生成的 CMD 形如:
```
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
```

---

## 3. 独立仓库推送 / 多分支合并预览验证

git 推送、`feat/*` 无冲突合并、`conflict/a + conflict/b` 冲突用例的步骤,
与 Flask 版完全相同,见 [`../python-demo/README.md`](../python-demo/README.md) 第 2、4 节
(把改动落到本目录的 `app.py` 即可,例如给 `index()` 加字段、或在 `GREETING` 默认值上制造冲突)。
