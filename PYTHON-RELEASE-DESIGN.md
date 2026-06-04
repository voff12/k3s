# Python 应用发布支持 — 技术方案

> 状态:设计稿(待评审 / 未实现)
> 适用范围:在现有 K3s DevOps 平台的两套流水线(DevOps 离线模式 + Release Harbor 模式)上新增 Python 应用的构建与发布能力,并复用已有的"多分支合并预览部署"。

---

## 一、背景:为什么现状是「Java 专用」

平台已内置两套集群内 Job 流水线,但构建逻辑硬编码了 Java/Maven:

| 位置 | Java 专用之处 |
|------|--------------|
| `DevOpsService.buildKanikoJob` 的 `rewrite-dockerfile` 容器 | 自动生成的多阶段 Dockerfile 固定为 `maven:3.9 AS builder` + `mvn` + `eclipse-temurin:17-jre`,`EXPOSE 8080` |
| `ReleaseService.buildReleaseJob` 的 `build` 容器 | 容器镜像为 `maven:3.9-eclipse-temurin-17`,执行 `mvn`,生成的 `Dockerfile.release` 为 `COPY target/*.jar` + `java -jar` |
| 默认 `buildCommand` | `mvn clean package -DskipTests` |
| 部署 | `containerPort` / `targetPort` 固定 `8080`(`deployToPreview`、`ensureService`、`deployToK3s`) |

**核心差异**:Java 有"编译期"(mvn 产出 jar);Python 没有编译,**依赖必须在镜像构建时 `pip install` 进镜像**。两者的启动方式、监听端口、基础镜像均不同。

---

## 二、已确认的设计决策

| 维度 | 决策 |
|------|------|
| 依赖/打包方式 | **pip + requirements.txt**(本期唯一支持;Poetry/Pipenv 后续迭代) |
| 启动方式 | **Web 服务(gunicorn / uvicorn)**,暴露 HTTP 端口供预览环境 NodePort 访问 |
| 语言识别 | **显式选择 + 自动探测兜底**(UI 选 Java/Python/自定义Dockerfile;留空则按仓库文件特征探测) |
| 端口 / 基础镜像 | **端口可配置 + 离线基础镜像**(`python:3.x-slim` 走 Harbor / 本地 registry 缓存) |
| C 扩展编译 | **slim 默认 + 可选编译链开关**:默认 `python:3.x-slim`(镜像小);需编译的包开启 `buildDeps` 后,生成的 Dockerfile 临时 `apt-get install build-essential gcc` |
| 仓库已有 Dockerfile | **仓库 Dockerfile 优先**:只要仓库存在 Dockerfile,即用它(仅重写 FROM);`runtime` 选择此时仅决定部署端口等,不覆盖用户构建产物 |

> ⚠️ **关于"离线"的准确语义**:本平台的"离线"指**基础镜像离线**(预热到 Harbor / 本地 registry),**不代表依赖离线**。`pip install` 仍需从某个 index 拉包,正如现有 Java 流水线的 `mvn` 必须连 aliyun 镜像。因此 **PyPI 索引(公共或私有镜像)必须对 K3s 节点可达**,否则构建必然失败。这一约束与现状一致,但需向使用者讲清。

---

## 三、设计核心:引入「运行时(runtime)」抽象

新增一个 `runtime` 维度,统一驱动四件事:**是否需要编译 / 生成什么 Dockerfile / 用什么基础镜像 / 默认端口与启动命令**。

| runtime | 编译步骤 | 生成的 Dockerfile | 基础镜像 | 默认端口 | 启动方式 |
|---------|---------|------------------|---------|---------|---------|
| `java`(现状) | mvn(Release 模式独立容器 / DevOps 模式在 Kaniko 内) | 多阶段 `maven` → `jre` | `eclipse-temurin` | 8080 | `java -jar` |
| `python`(新增) | 无,pip 在镜像构建时安装 | 单阶段 `pip install` | `python:3.x-slim` | 8000 | `gunicorn` / `uvicorn` |
| `dockerfile`(显式化) | 用户自带 | 不生成,仅重写 FROM 到本地 registry | 用户指定 | 用户指定 | 用户指定 |

> `dockerfile` 模式当前已隐含支持:`rewrite-dockerfile` 容器对已存在的用户 Dockerfile 只重写 FROM(与语言无关)。本次只把它在 UI 上显式化,不改逻辑。

---

## 四、数据模型改动

`PipelineConfig`(离线模式)与 `ReleaseConfig`(Harbor 模式)新增以下字段,保持向后兼容:

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `runtime` | `auto` | `java` / `python` / `dockerfile` / `auto` |
| `pythonVersion` | `3.11` | 基础镜像 `python:<ver>-slim` |
| `appPort` | 随 runtime(java=8080,python=8000) | 容器监听端口,端到端生效 |
| `pythonServer` | `gunicorn` | `gunicorn`(WSGI:Flask/Django)/ `uvicorn`(ASGI:FastAPI) |
| `appModule` | `app:app` | WSGI/ASGI 入口,如 `main:app`、`myproj.wsgi:application` |
| `requirementsPath` | `requirements.txt` | 依赖清单路径;**文件不存在时跳过 pip 安装步骤**(纯脚本/无第三方依赖) |
| `buildDeps` | `false` | 需要 C 扩展编译时置 `true`,生成的 Dockerfile 临时安装 `build-essential gcc` |
| `startCommand` | 空 | 可选,完全自定义启动命令(覆盖自动拼装,用于非 Web/worker 场景);以 `CMD ["sh","-c","<startCommand>"]` shell 形式注入 |

派生方法建议:`resolveRuntime()`(显式优先,否则交由容器内探测)、`getEffectiveAppPort()`、`isPython()`。

### 4.1 输入校验(安全)

新增字段会被拼入生成的 Dockerfile / shell `printf`,与分支名一样需校验,防 shell 注入(参照已有的 `isSafeBranchName`):

- `appPort`:整数,范围 `1–65535`
- `appModule`:白名单字符 `[A-Za-z0-9_.:]+`(模块路径 + `:` 分隔变量)
- `pythonVersion`:白名单 `[0-9.]+`
- `requirementsPath`:相对路径白名单,禁止 `..` 与绝对路径
- `pythonServer`:仅允许枚举 `gunicorn` / `uvicorn`
- `startCommand`:不做内容白名单(用户自负),但以 shell 形式注入并在 UI 标注风险

---

## 五、自动生成的 Python Dockerfile(单阶段、离线友好)

当 `runtime` 解析为 `python` **且用户仓库无 Dockerfile** 时,由容器内脚本生成。下面是各开关展开后的完整形态(`<...>` 为按字段渲染的占位):

```dockerfile
FROM <registry>/library/python:<pythonVersion>-slim
WORKDIR /app

# buildDeps=true 时注入(装完即用,镜像仍较小;如需更小可后续做多阶段)
RUN apt-get update && apt-get install -y --no-install-recommends build-essential gcc \
    && rm -rf /var/lib/apt/lists/*

# requirementsPath 存在时才生成这两行
COPY <requirementsPath> ./requirements.txt
RUN pip install --no-cache-dir -i <pip 镜像源> -r requirements.txt

# 始终补装所选 Web 服务器(用户 requirements 未含也能起服务)
RUN pip install --no-cache-dir -i <pip 镜像源> <gunicorn|uvicorn>

COPY . .
EXPOSE <appPort>

# 按 pythonServer 渲染其一:
# gunicorn(WSGI:Flask/Django)
CMD ["gunicorn", "-b", "0.0.0.0:<appPort>", "<appModule>"]
# uvicorn(ASGI:FastAPI)
# CMD ["uvicorn", "<appModule>", "--host", "0.0.0.0", "--port", "<appPort>"]
# startCommand 非空时,以上一律替换为:
# CMD ["sh", "-c", "<startCommand>"]
```

同时生成一个 **`.dockerignore`** 排除 `.git`、`__pycache__`、`*.pyc`、`venv` 等,避免 `COPY . .` 把仓库历史与缓存打进镜像。

要点:
- **pip 镜像源**:复用现有 Maven aliyun 镜像的同款思路,注入清华 / 阿里 `-i` 源(见第二节"离线语义":索引必须可达)。
- **CMD 与 pythonServer 严格对应**:gunicorn 用 `-b host:port module`;uvicorn 用 `module --host --port`,不再只在注释里给。
- **requirements 缺失即跳过 pip**:由 `requirementsPath` 是否存在决定,避免 `COPY` 失败。
- **依赖缓存**:依赖 Kaniko 层缓存(`--cache=true --cache-repo=...kaniko-cache`,现已启用)。`requirements.txt` 不变时 `pip install` 层命中缓存,**无需新增 PVC**。
- **端口一致**:`EXPOSE` / `CMD` 绑定端口与 `appPort` 同源,且与 Service `targetPort` 一致。

---

## 六、运行时解析与语言探测(clone 之后在容器内执行)

**统一的优先级规则(消除"显式优先"与"Dockerfile 优先"的矛盾),两套流水线一致:**

1. **仓库存在 Dockerfile → 永远走 `dockerfile` 模式**(仅重写 FROM 到本地 registry),与 `runtime` 选择、与语言无关。此时 `runtime` 字段**仅用于决定部署端口 `appPort` 等运行期参数**,不覆盖用户的构建产物。
2. 仓库无 Dockerfile 时,看 `runtime`:
   - 显式 `java` / `python` → 用对应模板生成 Dockerfile。
   - `auto` → 按文件特征探测:
     - **同时存在 `pom.xml`/`build.gradle` 与 `requirements.txt` → 直接报错**,要求显式指定 runtime(防止 Java 项目因辅助 `requirements.txt` 被误判成 Python、或反向误判 —— 杜绝 Python/Java 流水线混淆);
     - 仅 `requirements.txt` → python;
     - 仅 `pom.xml`/`build.gradle` → java;
     - 仅 `pyproject.toml`/`Pipfile`(无 requirements.txt)→ 报错(Poetry/Pipenv 未支持);
     - 都没有 → 失败,提示「请显式指定 runtime」。

> 即:**Dockerfile 优先于一切**;只有在没有 Dockerfile 时,显式 `runtime` 才生效;`auto` 是显式为空时的兜底。

> ⚠️ **重要(模式一致性,见 A)**:此规则**两套流水线统一适用**。但 Release(Harbor)模式现状是**无条件生成 `Dockerfile.release`、忽略用户 Dockerfile**——本期需为 Release 模式**新增"检测用户 Dockerfile → 优先使用"逻辑**(见 7.2)。这会轻微改变现有 Java Release 行为:仓库带 Dockerfile 的 Java 项目将不再走 jar 模板,而是用其自带 Dockerfile。视为有意的一致性收敛,需在变更说明中标注。

**Poetry / pyproject 的处理(见 B)**:本期仅支持 pip + `requirements.txt`,因此 **auto 探测只认 `requirements.txt`**。仅有 `pyproject.toml` / `Pipfile`(无 requirements.txt)的仓库**不**判为可构建的 python,而是**明确报错**:「检测到 pyproject/Pipfile 但缺少 requirements.txt,本期未支持 Poetry/Pipenv,请先 `poetry export -f requirements.txt -o requirements.txt` 或自带 Dockerfile」。避免"探测成功、依赖装不上"的假阳性。

**auto 的适用边界**:`auto` 对 `java` / `dockerfile` 很可靠;但 **Python web 需要 `appModule` / `appPort` 等 web 专属参数**,这些字段只有在 UI 显式选了 Python 时才会填写。因此 **Python web 强烈建议显式选择 runtime**;若 auto 探测到 Python 而这些字段为空,则回退默认(`app:app` / `8000`)并在日志中告警提示用户显式配置。

---

## 七、两套流水线的改造点

### 7.1 DevOps 离线模式(`DevOpsService.buildKanikoJob`)

- `rewrite-dockerfile` 容器脚本:按 `runtime` 分支,Python 走第五节单阶段模板;`auto` 时先探测。
- 其余逻辑(FROM 重写到本地 registry、Kaniko `--no-push` → tar、`ctr import` 到节点)**完全复用**。

### 7.2 Release Harbor 模式(`ReleaseService.buildReleaseJob`)

> **行为变更(见 A)**:现状 Release 模式无条件生成 `Dockerfile.release` 并忽略用户 Dockerfile。本期改为先**检测仓库是否自带 Dockerfile**:
> - 自带 Dockerfile → **优先使用**(重写 FROM 到 Harbor/本地 registry),kaniko `--dockerfile` 指向它;不再生成 jar 模板。**这会改变带 Dockerfile 的 Java 项目的现有行为**,需在发布说明中标注。
> - 无 Dockerfile → 按 runtime 生成(Java jar 模板 / Python 模板)。

- `build` 容器:
  - Java(无 Dockerfile)保持 `maven` 镜像执行 `mvn`(挂载 `maven-repo-pvc`)。
  - **Python / dockerfile 模式改为仅 clone + 写 Harbor 认证**(用 `git` / `alpine` 镜像),不挂 `maven-repo-pvc`,不编译。
- ⚠️ **衔接点(易漏)**:现有 Java `build` 容器顺带把 Harbor `docker-config/config.json` 写入,供后续 `kaniko-build` 推 Harbor。当 build 容器降级为"仅 clone"(Python / dockerfile 模式)后,**这段 docker-config 写入必须保留**(放在 clone 之后),否则 Kaniko 推 Harbor 会因缺认证失败。
- 生成 / 使用的 Dockerfile:
  - 自带 Dockerfile → 重写 FROM 后直接用。
  - Java 无 Dockerfile → 现状 runtime-only(`COPY target/*.jar`)。
  - Python 无 Dockerfile → 单阶段 `pip install` 模板(见第五节)。
- 基础镜像:Python 用 `python:<ver>-slim`(走 Harbor 缓存),并参与 FROM 可用性检查。

### 7.3 部署(两套的 `deployToPreview` / `ensureService` / `deployToK3s`)

- 把硬编码的 `8080` 统一改为 `config.getEffectiveAppPort()`:`containerPort` + Service `targetPort` 同步。
- 这是端到端打通端口的关键改动,影响面最广,**建议最先做**。

---

## 八、前端(`devops.html` / `release.html`)

- 新增**运行时下拉**:Java / Python / 自定义 Dockerfile / 自动探测。
- 选 **Python** 时显示:Python 版本、应用端口、服务类型(gunicorn / uvicorn)、入口模块(`app:app`)、requirements 路径、**「需要编译依赖」开关(buildDeps)**、可选 `startCommand`;隐藏「Maven 构建命令」。
- 选 **Java** 时维持现状字段。
- **提示文案**:注明"仓库若已有 Dockerfile,将优先使用它(仅重写 FROM),runtime 选择仅决定部署端口";`startCommand` 旁标注安全风险。
- 前端对 `appPort`(数字范围)、`appModule`、`pythonVersion` 做基础校验,后端按 4.1 再校验一次。
- 这些字段同样接入「多分支合并预览部署」——Python 应用也能多分支合并预览。

---

## 九、运维 / 离线前置(必须)

- **预热 Python 基础镜像**:将 `python:3.11-slim`(及所选版本)推送到内网 Harbor / 本地 registry,并补进 `prewarm-images.sh` 与文档。否则离线环境构建会卡在拉取基础镜像(`ImagePullBackOff`)。
- **PyPI 索引必须可达**(强约束,非可选):`pip install` 会从 index 拉包,K3s 节点必须能访问公共镜像(清华 / 阿里)**或**内网私有 PyPI。这与现有 Java 流水线 `mvn` 必须连 aliyun 镜像同理——"离线"仅指镜像,不含依赖。索引不可达时构建必失败。
- **`buildDeps=true` 的 apt 源**:开启编译链时 `apt-get install build-essential`,节点同样需能访问 Debian apt 源(或配内网 apt 镜像);否则 apt 步骤失败。

---

## 十、风险与取舍

1. **WSGI vs ASGI**:gunicorn 配 Flask/Django;uvicorn 配 FastAPI;ASGI 也可用 `gunicorn -k uvicorn.workers.UvicornWorker`。由 `pythonServer` + `appModule` 决定,生成的 `CMD` 严格对应所选服务器。
2. **端口一致性**:生成的 `CMD` 绑定 `0.0.0.0:appPort`,必须与 Service / 探针端口一致 —— 因此端口需一处配置、处处生效。
3. **C 扩展编译**:slim 缺 gcc,带 C 扩展且无 wheel 的包会装失败 → 用 `buildDeps` 开关临时装编译链解决;镜像会变大,如需更小可后续做"编译阶段 + 运行阶段"多阶段。
4. **预览环境无健康探针**(沿用现状):Python 应用健康路径各异,本期不加探针避免误判;后续可做可选 health path。
5. **非 Web Python(worker / CLI)**:本期按 Web 服务优化;非 Web 场景用 `startCommand` 自定义覆盖(`CMD` 直接采用)。
6. **gunicorn/uvicorn 版本**:自动补装时不锁版本,可能与用户依赖冲突;可后续支持在 `requirements.txt` 中自带以精确控制。
7. **输入注入**:新增字段拼入生成脚本,须按 4.1 校验;`startCommand` 为用户自负的逃生通道,UI 需标注风险。
8. **框架能力边界(见 C)**:通用模板只做 `pip install` + 起服务,**不**执行 `migrate` / `collectstatic` / 设 `DJANGO_SETTINGS_MODULE`。Flask / FastAPI 单应用开箱即用;**Django 等需要迁移/静态/环境变量的框架**,请用 `startCommand` 串联(如 `python manage.py migrate && gunicorn proj.wsgi`)或自带 Dockerfile。UI 与文档需写明此边界。
9. **日志/步骤文案硬编码 "Maven"(见 D)**:`DevOpsService` 现有日志写死"步骤2/5: Kaniko 多阶段构建 (Maven 打包...)"。Python 构建时这些文案会误导。实现时需把相关日志 / 步骤标题改为 **runtime 感知**(Java 显示"Maven 打包",Python 显示"pip 安装")或中性措辞(如"依赖安装 + 镜像构建")。
10. **离线多节点镜像局部性(见 E,预存约束)**:DevOps 离线模式 `ctr import` 只把镜像导入**运行构建 Job 的那个节点**;若预览 Deployment 被调度到其它节点 → `ImagePullBackOff`。对 Java 同样存在,但临时预览环境更易触发。**缓解**:单节点集群无影响;多节点可给预览 Pod 加 `nodeSelector`/`nodeAffinity` 绑定到构建节点,或改用 Harbor 推送(Release 模式天然规避)。本期文档列为已知限制。
11. **预览 Pod 无环境变量/Secret 注入(跨语言,非 Python 专属)**:当前预览 Deployment 不支持注入 env / Secret。Python 应用常依赖 `DATABASE_URL`、`SECRET_KEY` 等环境变量才能起服务,Django 尤甚——这会削弱"联调/集成测试"预览的可用性(Java 同样受限)。**本期不做**,但建议作为预览功能的后续增强(给 `PipelineConfig`/`ReleaseConfig` 增加 `env` 键值表,部署时注入预览 Deployment)。

> **后续增强 backlog**:① Poetry/Pipenv(`pip install .` 或 export);② 编译阶段 + 运行阶段多阶段瘦身;③ 预览 Pod 环境变量/Secret 注入;④ 可选健康探针(health path)。

---

## 十一、改动文件清单(预估)

| 文件 | 改动 |
|------|------|
| `model/PipelineConfig.java`、`model/ReleaseConfig.java` | 新增 runtime / pythonVersion / appPort / pythonServer / appModule / requirementsPath / buildDeps / startCommand 字段 + 派生方法 |
| `service/DevOpsService.java`(`buildKanikoJob` + 日志文案) | Python Dockerfile 模板 / 探测分支 / Dockerfile 优先逻辑;**步骤日志改 runtime 感知**(去掉写死的 "Maven") |
| `service/ReleaseService.java`(`buildReleaseJob`) | **新增检测用户 Dockerfile → 优先使用**(行为变更);build 容器分支(Python/dockerfile 仅 clone + **保留 docker-config 写入**)+ Python 模板 + 基础镜像 |
| 两套 `deployToPreview` / `deployToK3s` / `ensureService` | 端口参数化(去掉硬编码 8080) |
| `controller/DevOpsController.java`、`controller/ReleaseController.java` | DTO 透传新字段 + **输入校验(4.1)** |
| `templates/devops.html`、`templates/release.html` | 运行时选择器 + Python 字段 + buildDeps 开关 + 提示文案 |
| `prewarm-images.sh`、`README*.md` | 预热 python 基础镜像 + 离线/索引约束 + 使用文档 |

---

## 十二、建议实施顺序

1. **模型字段**(`PipelineConfig` / `ReleaseConfig` + 派生方法)
2. **端口参数化**(影响面最广,先打通 `appPort` 端到端)
3. **DevOps 离线模式** Python 模板 + 自动探测
4. **Release Harbor 模式** Python 分支
5. **前端**运行时选择器
6. **离线预热文档**(python 基础镜像)

---

## 十三、验证方式(端到端,实现后)

1. 准备一个最小 Flask/FastAPI 仓库(含 `requirements.txt` + `app.py` 暴露 `app`)。
2. `/devops` 或 `/release` 新建,runtime 选 Python,填入口模块与端口 → 触发。
3. 观察 SSE:clone →(探测/生成 Python Dockerfile)→ Kaniko 构建 → 导入/推送 → 部署。
4. `kubectl get pod` 确认镜像基于 `python:slim`,`curl http://<节点IP>:<NodePort>/` 返回应用响应。
5. **多分支合并预览**:Python 仓库选 base + N 分支 → 部署到 `preview-<id>` → 访问预览 URL。
6. **向后兼容**:不选 runtime(auto)的 Java 仓库行为不变。
