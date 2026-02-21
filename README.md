# K3s 集群管理与 DevOps 平台

基于 Spring Boot 3.2.2 的 K3s 轻量级 Kubernetes 集群管理平台，集成 CI/CD 流水线、Pod 终端、AI 智能分析等功能，支持完全离线部署。

---

## 功能概览

| 模块 | 功能 | 说明 |
|------|------|------|
| 集群仪表盘 | 节点/Pod/Deployment 概览 | 实时健康状态、近期事件、AI 事件分析 |
| Pod 管理 | 列表、详情、日志、YAML | 支持搜索过滤、状态筛选、命名空间切换 |
| Pod 终端 | WebSocket 交互式终端 | 基于 xterm.js，支持多容器选择、ANSI 颜色 |
| 部署管理 | 创建、扩缩容、更新 | 镜像更新、资源配置、环境变量修改 |
| 内存管理 | 集群内存分析 | 节点/Pod 内存排行、AI 优化建议、一键调整 |
| 存储管理 | PV/磁盘概览 | PersistentVolume 状态、节点磁盘用量 |
| CI/CD 流水线 | 代码到部署全链路 | Git → Maven → Kaniko → K3s，实时日志流 |
| 应用发布 | Git 到 Harbor 到 K3s | Git 克隆 → Maven 构建 → Kaniko 推送 Harbor → K3s 部署 |
| AI 工具 | Kubernetes 智能问答 | 基于通义千问，流式响应，Markdown 渲染 |

---

## 技术栈

- **后端**: Spring Boot 3.2.2 + Spring WebSocket + Spring WebFlux
- **K8s 客户端**: Fabric8 Kubernetes Client 6.10.0
- **模板引擎**: Thymeleaf
- **前端**: Tailwind CSS + Material Icons + xterm.js
- **实时通信**: WebSocket（终端）+ SSE（流水线日志/AI 流式响应）
- **AI**: 阿里云通义千问（OpenAI 兼容协议）
- **容器构建**: Kaniko（无 Docker daemon）
- **运行环境**: K3s + containerd

---

## 项目结构

```
k3s/
├── pom.xml                          # Maven 构建配置
├── Dockerfile                       # 应用容器镜像
├── build.sh                         # 本地构建脚本
├── prewarm-images.sh                # 离线镜像预热（三阶段）
├── find_k3s_config.sh               # K3s 配置发现工具
├── k8s-rbac.yaml                    # RBAC 权限配置
│
├── src/main/java/com/example/k3sdemo/
│   ├── K3sDemoApplication.java      # 应用入口
│   ├── config/
│   │   └── WebSocketConfig.java     # WebSocket 配置
│   ├── controller/
│   │   ├── DashboardController.java # 仪表盘 (/dashboard)
│   │   ├── PodController.java       # Pod 管理 (/)
│   │   ├── MemoryController.java    # 内存管理 (/memory)
│   │   ├── StoreController.java     # 存储管理 (/store)
│   │   ├── DevOpsController.java    # CI/CD 流水线 (/devops)
│   │   ├── ReleaseController.java   # 应用发布 (/release)
│   │   └── AiToolsController.java   # AI 工具 (/aitools)
│   ├── service/
│   │   ├── DevOpsService.java       # 流水线编排引擎
│   │   ├── ReleaseService.java      # 发布服务（Git → Harbor → K3s）
│   │   └── QwenService.java         # 通义千问 AI 服务
│   ├── handler/
│   │   └── TerminalWebSocketHandler.java  # Pod 终端
│   └── model/                       # 视图模型
│       ├── PipelineConfig.java      # 流水线配置
│       ├── PipelineRun.java         # 流水线运行状态
│       ├── ReleaseConfig.java       # 发布配置
│       ├── ReleaseRecord.java       # 发布记录
│       └── ...ViewModel.java        # 各页面视图模型
│
└── src/main/resources/
    ├── application.properties       # 应用配置
    └── templates/                   # Thymeleaf 页面
        ├── dashboard.html           # 集群仪表盘
        ├── pods.html                # Pod 列表
        ├── poddetail.html           # Pod 详情
        ├── podterm.html             # Pod 终端
        ├── memory.html              # 内存管理
        ├── store.html               # 存储管理
        ├── devops.html              # CI/CD 流水线
        ├── release.html             # 应用发布
        └── aitools.html             # AI 工具
```

---

## 快速开始

### 前置条件

- K3s 集群已运行
- Java 17+
- Maven 3.9+

### 1. 配置 RBAC 权限

```bash
kubectl apply -f k8s-rbac.yaml
```

### 2. 查找 K3s 配置文件

```bash
bash find_k3s_config.sh
```

### 3. 修改配置

编辑 `src/main/resources/application.properties`：

```properties
# K3s 连接（默认路径，按需修改）
k8s.kubeconfig=/etc/rancher/k3s/k3s.yaml

# 通义千问 API（可选，用于 AI 功能）
qwen.api.key=你的API密钥
qwen.api.model=qwen-max

# Harbor 镜像仓库（发布功能必需）
harbor.host=harbor.local:30002
harbor.ip=你的Harbor服务器IP
harbor.username=admin
harbor.password=你的Harbor密码
```

### 4. 构建运行

```bash
mvn clean package -DskipTests
java -jar target/k3s-1.0.0-SNAPSHOT.jar
```

访问 http://localhost:8080

### 5. 使用应用发布功能

配置 Harbor 后，访问 `/release` 页面即可使用应用发布功能：

1. **填写发布配置**：
   - Git 仓库地址（必填）
   - 镜像名称（必填）
   - Deployment 名称（可选，留空则仅构建推送镜像）
   - Harbor 项目（默认：library）

2. **触发发布**：
   - 系统自动执行：Git 克隆 → Maven 构建 → Kaniko 构建 → 推送 Harbor → 部署 K3s

3. **查看实时日志**：
   - 页面实时显示构建和部署日志
   - 支持 SSE 流式推送

### 6. Docker 部署（可选）

```bash
bash build.sh
# 生成 k3s.tar，传输到 K3s 节点后导入:
k3s ctr images import k3s.tar
```

---

## 应用发布

### 发布功能概述

应用发布功能提供从 Git 代码仓库到 Harbor 镜像仓库再到 K3s 集群的完整自动化流程，支持一键构建、推送和部署。

### 发布流程架构

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  Git Clone  │───▶│ Maven Build  │───▶│ Kaniko Build │
│  克隆代码仓库  │    │ Maven 编译打包 │    │ 构建容器镜像  │
└──────────────┘    └──────────────┘    └──────────────┘
                                              │
      ┌──────────────┐    ┌──────────────┐    ▼
      │ K3s Deploy   │◀───│ Push Harbor  │◀───┌──────────────┐
      │ 部署到集群    │    │ 推送到 Harbor │    │ Dockerfile   │
      │ (自动创建/更新)│    │ (认证已配置)  │    │ 自动生成      │
      └──────────────┘    └──────────────┘    └──────────────┘
```

### 发布流程特性

- **两步自动化流程**：
  1. **构建发布阶段**：Git 克隆 → Maven 构建 → 自动生成 Dockerfile → Kaniko 构建镜像 → 推送到 Harbor
  2. **K3s 部署阶段**：自动更新或创建 Deployment → 滚动更新 → 状态监控

- **Harbor 集成**：
  - 自动配置 Harbor 认证（Base64 编码）
  - 支持自定义 Harbor 项目和命名空间
  - 镜像自动推送到 `harbor.host/project/imageName:tag`

- **智能 Deployment 管理**：
  - **自动命名**：如果未指定 Deployment 名称，自动使用镜像名称（符合 K8s 命名规范）
  - **自动创建**：如果 Deployment 不存在，自动创建基本配置
  - **自动更新**：如果 Deployment 存在，自动更新镜像并触发滚动更新
  - 支持自定义命名空间和资源限制

- **实时日志流**：
  - SSE 流式推送构建和部署日志
  - 实时显示每个步骤的执行状态
  - 支持错误诊断和超时保护（30 分钟）

- **灵活配置**：
  - 支持自定义 Git 分支、镜像标签、Harbor 项目
  - 支持私有 Git 仓库（Token 认证）
  - 支持自定义 Maven 构建命令
  - 可选 Deployment 名称（留空则仅构建推送镜像）

### 发布配置参数

| 参数 | 说明 | 默认值 | 必填 |
|------|------|--------|------|
| `gitUrl` | Git 仓库地址 | - | ✅ |
| `branch` | Git 分支名 | main | - |
| `imageName` | 镜像名称 | - | ✅ |
| `imageTag` | 镜像标签 | latest | - |
| `namespace` | K8s 命名空间 | default | - |
| `deploymentName` | Deployment 名称 | 自动使用镜像名称 | ⚠️ 留空则自动生成 |
| `harborProject` | Harbor 项目 | library | - |
| `buildCommand` | Maven 构建命令 | mvn clean package -DskipTests | - |
| `gitToken` | Git 认证 Token | - | - |

### 使用示例

1. **自动部署**（推荐）：
   - 填写 Git 仓库地址和镜像名称（如 `hello`）
   - Deployment 名称留空，系统会自动使用镜像名称作为 Deployment 名称
   - 系统会构建镜像、推送到 Harbor，并自动创建或更新 Deployment

2. **部署到现有 Deployment**：
   - 填写 Git 仓库地址、镜像名称和指定的 Deployment 名称（如 `springboot-app`）
   - 系统会更新该 Deployment 的镜像并触发滚动更新

3. **自定义 Deployment 名称**：
   - 填写 Git 仓库地址、镜像名称和自定义 Deployment 名称（如 `my-app`）
   - 系统会使用指定的名称创建或更新 Deployment

**注意**：Deployment 名称会自动转换为符合 K8s 命名规范的格式（小写字母、数字、连字符）。

### Harbor 认证配置

发布功能需要正确配置 Harbor 认证信息：

```properties
# Harbor 镜像仓库配置
harbor.host=harbor.local:30002
harbor.ip=60.205.252.82          # Harbor 服务器 IP（用于 Pod 内域名解析）
harbor.project=library           # 默认 Harbor 项目
harbor.username=admin             # Harbor 用户名
harbor.password=Harbor12345      # Harbor 密码
```

**重要提示**：
- 确保 Harbor 用户对指定项目有 **push** 权限
- Harbor 认证使用 Base64 编码，自动处理
- 如果 Harbor 使用自签名证书，Kaniko 会自动跳过 TLS 验证

---

## CI/CD 流水线

### 流水线架构

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ registry-check│───▶│  git-clone   │───▶│ maven-build  │
│ 检查本地 Registry│    │ 克隆代码仓库  │    │ Maven 编译打包 │
└──────────────┘    └──────────────┘    └──────────────┘
                                              │
      ┌──────────────┐    ┌──────────────┐    ▼
      │    loader     │◀───│    kaniko     │◀───┌──────────────┐
      │ 导入到 K3s    │    │ 构建容器镜像  │    │rewrite-dockerfile│
      │ containerd    │    │ (离线模式)    │    │ 智能 Dockerfile │
      └──────────────┘    └──────────────┘    └──────────────┘
```

### 流水线特性

- **6 步全自动**: 代码克隆 → Maven 打包 → Dockerfile 处理 → Kaniko 构建 → 导入 K3s → 更新 Deployment
- **离线模式**: 基础镜像从 `localhost:5000` 拉取，无需外网
- **智能 Dockerfile**: 自动检测基础镜像可用性，不可用时自动生成基于 `eclipse-temurin:17-jre-jammy` 的 Dockerfile
- **实时日志**: SSE 流式推送，前端实时展示构建进度
- **多层防御**: API 提交防御、Pod 调度防御、构建失败诊断、30 分钟超时保护
- **私有仓库支持**: GitLab/GitHub Token 认证
- **网络代理**: Git HTTP 代理配置，适配受限网络环境

### 离线镜像预热

首次使用流水线前，需要预热容器镜像：

```bash
sudo bash prewarm-images.sh
```

三阶段预热流程：

| 阶段 | 内容 | 说明 |
|------|------|------|
| 阶段 1 | Job 容器镜像 → K3s containerd | Alpine、Maven、Kaniko、K3s loader、Registry |
| 阶段 2 | 部署本地 Registry | `localhost:5000`，存储在 `/opt/local-registry` |
| 阶段 3 | 基础镜像 → localhost:5000 | `eclipse-temurin:17-jre-jammy`，优先本地推送 |

镜像源优先级：本地 containerd → 阿里云 → DaoCloud → xuanyuan.me → Docker Hub

### 流水线配置参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `gitUrl` | Git 仓库地址 | 必填 |
| `branch` | 分支名 | master |
| `imageName` | 构建镜像名 | 必填 |
| `imageTag` | 镜像标签 | latest |
| `namespace` | K8s 命名空间 | default |
| `deploymentName` | 目标 Deployment | - |
| `dockerfilePath` | Dockerfile 路径 | ./Dockerfile |
| `buildCommand` | Maven 构建命令 | mvn clean package -DskipTests |
| `gitToken` | Git 认证 Token | - |
| `gitProxy` | Git HTTP 代理 | - |

---

## API 端点

### 仪表盘

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/dashboard` | 集群概览页面 |
| POST | `/dashboard/analyze-event` | AI 分析 K8s 事件 |

### Pod 管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | Pod 列表（支持 `search`、`status`、`namespace` 参数） |
| POST | `/deploy` | 创建 Deployment |
| GET | `/pods/{ns}/{name}` | Pod 详情页 |
| GET | `/pods/{ns}/{name}/logs` | Pod 日志（支持 `keyword`、`lines`） |
| GET | `/pods/{ns}/{name}/yaml` | Pod YAML 导出 |
| POST | `/pods/{ns}/{name}/delete` | 删除 Pod |
| POST | `/pods/{ns}/{name}/scale` | 扩缩容 |
| POST | `/pods/{ns}/{name}/update` | 更新 Deployment 配置 |
| GET | `/pods/{ns}/{name}/deployment` | 获取 Deployment 信息（JSON） |
| GET | `/pods/{ns}/{name}/terminal` | Pod 终端页面 |

### 内存管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/memory` | 内存概览页面 |
| GET | `/memory/ai-suggestions` | AI 内存优化建议（JSON） |
| POST | `/memory/apply-suggestion` | 应用内存调整建议 |

### 存储管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/store` | 存储概览页面 |

### CI/CD 流水线

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/devops` | 流水线仪表盘 |
| POST | `/devops/pipeline/run` | 触发流水线 |
| GET | `/devops/pipeline/{id}/stream` | SSE 实时日志流 |
| GET | `/devops/pipeline/{id}/status` | 流水线状态（JSON） |
| GET | `/devops/pipelines` | 流水线列表（JSON） |

### 应用发布

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/release` | 发布管理页面 |
| POST | `/release/run` | 触发发布（JSON Body: ReleaseConfig） |
| GET | `/release/{id}/stream` | SSE 实时日志流 |
| GET | `/release/{id}/status` | 发布状态（JSON） |
| GET | `/release/list` | 发布记录列表（JSON） |

### AI 工具

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/aitools` | AI 聊天界面 |
| POST | `/aitools/chat` | 流式 AI 对话（SSE） |

### WebSocket

| 路径 | 参数 | 说明 |
|------|------|------|
| `ws://host:8080/terminal` | `namespace`、`pod`、`container` | Pod 交互式终端 |

---

## 配置说明

```properties
# ==================== 服务器 ====================
server.port=8080

# ==================== K3s 连接 ====================
k8s.kubeconfig=/etc/rancher/k3s/k3s.yaml

# ==================== AI 配置（可选）====================
qwen.api.key=你的通义千问API密钥
qwen.api.url=https://dashscope.aliyuncs.com/compatible-mode/v1
qwen.api.model=qwen-max          # 可选: qwen-plus / qwen-turbo

# ==================== Harbor 镜像仓库 ====================
harbor.host=harbor.local:30002    # Harbor 地址（含端口）
harbor.ip=60.205.252.82           # Harbor IP（用于 Pod 内域名解析）
harbor.project=library             # 默认 Harbor 项目
harbor.username=admin              # Harbor 用户名
harbor.password=Harbor12345        # Harbor 密码（确保有 push 权限）

# ==================== Git 配置（可选）====================
gitlab.token=                     # 私有仓库 Token
git.proxy=                        # HTTP 代理，如 http://proxy:7890

# ==================== 离线流水线 ====================
local.registry=localhost:5000     # 本地镜像仓库
kaniko.image=registry.aliyuncs.com/kaniko-project/executor:latest
git.image=alpine:3.19
maven.image=maven:3.9-eclipse-temurin-17
loader.image=rancher/k3s:latest

# ==================== 发布功能（可选）====================
release.base-image=                # 自定义基础镜像（留空使用 Harbor 中的镜像）
```

---

## RBAC 权限

平台需要以下 Kubernetes 权限，通过 `k8s-rbac.yaml` 配置：

**集群级（ClusterRole）**：

| 资源 | 权限 |
|------|------|
| Nodes、PersistentVolumes、Events | get、list、watch |
| Pods、Deployments、ReplicaSets | get、list、watch（跨命名空间） |

**命名空间级（Role，default 命名空间）**：

| 资源 | 权限 |
|------|------|
| Pods | 完整 CRUD |
| Pods/exec | create、get（终端功能） |
| Deployments、Deployments/scale | 完整 CRUD |
| ReplicaSets | get、list、watch |

---

## 构建脚本

### build.sh — 本地构建

```bash
bash build.sh
# 执行: mvn clean package → docker buildx build → docker save
# 产出: k3s.tar (约 466MB)
```

### prewarm-images.sh — 离线镜像预热

```bash
sudo bash prewarm-images.sh
# 三阶段: 拉取 Job 镜像 → 部署本地 Registry → 推送基础镜像
```

### find_k3s_config.sh — 配置发现

```bash
bash find_k3s_config.sh
# 搜索: /etc/rancher/k3s/k3s.yaml → ~/.kube/config → ~/.kube/k3s.yaml
```

---

## 离线部署指南

适用于无法访问外网的 K3s 节点：

```bash
# 1. 在有网络的机器上准备镜像
docker pull eclipse-temurin:17-jdk-jammy
docker save eclipse-temurin:17-jdk-jammy -o temurin.tar

docker pull alpine:3.19
docker save alpine:3.19 -o alpine.tar

docker pull maven:3.9-eclipse-temurin-17
docker save maven:3.9-eclipse-temurin-17 -o maven.tar

# 2. 传输到 K3s 节点
scp *.tar root@<K3s节点>:/root/

# 3. 在 K3s 节点上导入
k3s ctr images import temurin.tar
k3s ctr images import alpine.tar
k3s ctr images import maven.tar

# 4. 执行预热脚本（自动部署 Registry 并推送基础镜像）
sudo bash prewarm-images.sh

# 5. 启动应用
java -jar k3s-1.0.0-SNAPSHOT.jar
```

---

## 故障排查

### Harbor 推送失败：UNAUTHORIZED

**问题**：镜像推送到 Harbor 时出现 `unauthorized to access repository` 错误。

**解决方案**：

1. **检查 Harbor 凭据**：
   ```bash
   # 手动测试 Harbor 登录
   docker login harbor.local:30002 -u admin -p Harbor12345
   ```

2. **验证项目权限**：
   - 登录 Harbor Web 界面
   - 确认用户对指定项目（如 `library`）有 **push** 权限
   - 检查项目是否为公开项目或需要认证

3. **检查配置**：
   - 确认 `application.properties` 中的 Harbor 配置正确
   - 检查 `harbor.host` 是否包含端口号（如 `harbor.local:30002`）
   - 验证 `harbor.ip` 是否正确（用于 Pod 内域名解析）

4. **查看发布日志**：
   - 在发布页面查看详细错误信息
   - 检查 Kaniko 容器的日志输出

### Harbor 镜像拉取失败：ImagePullBackOff

**问题**：Deployment 创建后，Pod 出现 `ImagePullBackOff` 错误，无法从 Harbor 拉取镜像。

**常见错误**：`http: server gave HTTP response to HTTPS client`

- **原因**：Harbor 使用 HTTP，但 K3s containerd 默认用 HTTPS 访问
- **解决**：配置 K3s 使用 HTTP 访问 Harbor，复制 `k3s-registries.yaml` 到节点并重启：
  ```bash
  sudo cp k3s-registries.yaml /etc/rancher/k3s/registries.yaml
  sudo systemctl restart k3s
  ```
  修改其中的 Harbor 地址、用户名、密码与 `application.properties` 一致。

**其他解决方案**：

1. **自动创建 Harbor Secret**：
   - 系统会在创建 Deployment 时自动创建 `harbor-registry-secret`
   - Secret 包含 Harbor 认证信息（Base64 编码）

2. **自动配置 imagePullSecrets**：
   - 新创建的 Deployment 会自动添加 `imagePullSecrets`
   - 现有 Deployment 更新时也会自动添加（如果缺失）

3. **手动验证 Secret**（如需要）：
   ```bash
   # 检查 Secret 是否存在
   kubectl get secret harbor-registry-secret -n default
   
   # 查看 Secret 详情
   kubectl describe secret harbor-registry-secret -n default
   ```

4. **手动创建 Secret**（如果自动创建失败）：
   ```bash
   kubectl create secret docker-registry harbor-registry-secret \
     --docker-server=harbor.local:30002 \
     --docker-username=admin \
     --docker-password=Harbor12345 \
     --namespace=default
   ```

5. **检查 Pod 状态**：
   ```bash
   # 查看 Pod 事件
   kubectl describe pod <pod-name> -n default
   
   # 查看 Pod 日志
   kubectl logs <pod-name> -n default
   ```

### Deployment 未自动创建

**问题**：镜像已推送，但 Deployment 未创建或更新。

**解决方案**：

- **自动命名**：如果未指定 Deployment 名称，系统会自动使用镜像名称（已转换为 K8s 命名规范）
- **检查日志**：查看发布日志中的 Deployment 创建/更新信息
- **命名规范**：Deployment 名称会自动转换（小写、去除特殊字符），确保符合 K8s 要求
- **权限检查**：确认 RBAC 配置允许创建 Deployment（参考 `k8s-rbac.yaml`）
- **手动检查**：使用 `kubectl get deployment -n <namespace>` 查看 Deployment 状态

### Git 克隆失败

**问题**：发布时 Git 克隆失败，如 `Failed to connect to github.com port 443`。

**解决方案**：

1. **无法直连 GitHub**：配置 Git 代理
   - 在发布表单填写 **Git 代理**（如 `http://192.168.1.100:7890`）
   - 或在 `application.properties` 设置 `git.proxy=http://代理IP:端口`
   - 代理地址需能被 K3s Pod 访问（使用宿主机/节点 IP，不要用 127.0.0.1）
2. **私有仓库**：填写 `gitToken` 字段（GitLab/GitHub Personal Access Token）
3. **分支不存在**：确认指定的分支名称正确

### Kaniko 构建超时

**问题**：镜像构建超过 30 分钟被终止。

**解决方案**：

- 检查 Dockerfile 是否优化（减少层数、使用缓存）
- 确认基础镜像可用（从 Harbor 或本地 Registry 拉取）
- 查看构建日志，定位具体卡住的步骤

---

## 许可证

MIT License
