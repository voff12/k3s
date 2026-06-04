# python-demo — Python 应用发布验证用最小示例

一个最小 Flask 应用,用于验证 K3s 平台的 **Python 应用发布** 与 **多分支合并预览部署**(见仓库根目录 `PYTHON-RELEASE-DESIGN.md`)。

```
python-demo/
├── app.py            # Flask 应用, WSGI 入口变量为 app(appModule = app:app)
├── requirements.txt  # Flask + gunicorn
├── .dockerignore     # 平台也会自动生成, 这里自带一份
└── .gitignore
```

> ⚠️ **故意不含 Dockerfile**:用于验证平台「无 Dockerfile → 按 runtime=python 自动生成」的路径。
> 若想改测「仓库 Dockerfile 优先」分支,在本目录加一个 Dockerfile 即可(平台会优先用它、仅重写 FROM)。

---

## 1. 本地快速跑通

```bash
cd examples/python-demo
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# 方式 A: 直接跑(调试)
python app.py
# 方式 B: 模拟平台启动方式
gunicorn -b 0.0.0.0:8000 app:app

curl http://localhost:8000/         # {"message": "...", "host": "...", "version": "dev"}
curl http://localhost:8000/health   # {"status": "UP"}
```

---

## 2. 作为独立仓库推送(平台按 gitUrl 克隆)

平台是按 `gitUrl` 克隆构建的,需先把本目录作为**独立仓库**推到 GitHub/GitLab(或内网 Git):

```bash
# 在 examples/python-demo 目录内
git init && git add . && git commit -m "init python-demo"
git branch -M main
git remote add origin <你的仓库地址>.git
git push -u origin main
```

---

## 3. 在平台上发布(两套流水线均可)

进入 `/devops`(离线模式)或 `/release`(Harbor 模式)→ 新建,关键字段:

| 字段 | 值 |
|------|-----|
| Git 仓库地址 | 上一步推送的仓库地址 |
| 运行时 runtime | **Python**(显式选择;Python web 建议显式,以便填 module/port) |
| 镜像名称 | `python-demo` |
| Python 版本 | `3.11` |
| 应用端口 appPort | `8000` |
| 服务类型 pythonServer | `gunicorn`(WSGI) |
| 入口模块 appModule | `app:app` |
| requirements 路径 | `requirements.txt` |
| 需要编译依赖 buildDeps | 关(本示例纯 wheel,无需 gcc) |

发布成功后:
- 离线模式:`curl http://<节点IP>:<NodePort>/`
- Harbor 模式:同上,镜像来自 Harbor

---

## 4. 多分支合并预览部署验证

### 4.1 正常合并(无冲突)

基于 main 建两个**改动不同位置**的分支,各加一个端点:

```bash
# 分支 feat/ping —— 在 app.py 末尾(if __name__ 之前)加:
#   @app.get("/ping")
#   def ping(): return jsonify(pong=True)
git checkout -b feat/ping && git commit -am "add /ping" && git push origin feat/ping

# 分支 feat/version-header —— 改 index() 的返回, 增加一个字段
git checkout main
git checkout -b feat/version-header && git commit -am "add build field" && git push origin feat/version-header
```

在 `/devops` 开「多分支合并预览部署」:base=`main` + 勾选 `feat/ping`、`feat/version-header` → 触发。
预期:走 `CLONING → MERGING → BUILDING → … → DEPLOYING`,生成 `preview-<id>` 命名空间,
`curl http://<节点IP>:<分配的NodePort>/ping` 可访问。

### 4.2 冲突用例(验证「中止并报告」)

建两个**改动同一行**的分支制造冲突:

```bash
# 分支 conflict/a —— 把 GREETING 默认值改成 "Hello A"
git checkout main && git checkout -b conflict/a
#   编辑 app.py: GREETING = os.environ.get("GREETING", "Hello A")
git commit -am "greeting A" && git push origin conflict/a

# 分支 conflict/b —— 把同一行改成 "Hello B"
git checkout main && git checkout -b conflict/b
#   编辑 app.py: GREETING = os.environ.get("GREETING", "Hello B")
git commit -am "greeting B" && git push origin conflict/b
```

合并部署选 base=`main` + `conflict/a` + `conflict/b` → 预期 **MERGING 阶段失败**,
SSE / 合并信息卡展示 `[CONFLICT]` 冲突文件列表(`app.py`),不创建预览环境。

---

## 5. 切换到 FastAPI / uvicorn(可选)

如需验证 ASGI 路径,把示例换成 FastAPI:

```python
# app.py
from fastapi import FastAPI
app = FastAPI()

@app.get("/")
def index(): return {"message": "hello", "version": "dev"}

@app.get("/health")
def health(): return {"status": "UP"}
```

```text
# requirements.txt
fastapi==0.111.0
uvicorn==0.30.1
```

平台字段把 **pythonServer 改为 `uvicorn`**,appModule 仍为 `app:app`,端口 8000。
