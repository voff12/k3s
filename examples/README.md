# examples — 平台验证用最小示例仓库

用于验证 K3s 平台「Python 应用发布 / 多分支合并预览部署」(设计见根目录 `PYTHON-RELEASE-DESIGN.md`)。
每个子目录都是**自包含**的,可单独 `git init` 推成独立仓库,再由平台按 gitUrl 克隆构建。

| 示例 | 框架 / 协议 | 服务器 | pythonServer | 说明 |
|------|------------|--------|--------------|------|
| [`python-demo`](python-demo/) | Flask / WSGI | gunicorn | `gunicorn` | 默认推荐路径 |
| [`python-fastapi-demo`](python-fastapi-demo/) | FastAPI / ASGI | uvicorn | `uvicorn` | ASGI 路径 |

共同点:
- 入口变量均为 `app`(`appModule = app:app`),默认端口 `8000`。
- **故意不含 Dockerfile** → 验证平台「无 Dockerfile → 自动生成」路径;加 Dockerfile 即改测「仓库 Dockerfile 优先」。
- 纯 wheel 依赖,`buildDeps` 保持关。

详细的本地运行、平台字段填法、多分支合并 / 冲突用例,见各自的 `README.md`。
