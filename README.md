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
│   │   └── AiToolsController.java   # AI 工具 (/aitools)
│   ├── service/
│   │   ├── DevOpsService.java       # 流水线编排引擎
│   │   └── QwenService.java         # 通义千问 AI 服务
│   ├── handler/
│   │   └── TerminalWebSocketHandler.java  # Pod 终端
│   └── model/                       # 视图模型
│       ├── PipelineConfig.java      # 流水线配置
│       ├── PipelineRun.java         # 流水线运行状态
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
```

### 4. 构建运行

```bash
mvn clean package -DskipTests
java -jar target/k3s-1.0.0-SNAPSHOT.jar
```

访问 http://localhost:8080

### 5. Docker 部署（可选）

```bash
bash build.sh
# 生成 k3s.tar，传输到 K3s 节点后导入:
k3s ctr images import k3s.tar
```

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
- **智能 Dockerfile**: 自动检测基础镜像可用性，不可用时自动生成基于 `eclipse-temurin:17-jdk-jammy` 的 Dockerfile
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
| 阶段 3 | 基础镜像 → localhost:5000 | `eclipse-temurin:17-jdk-jammy`，优先本地推送 |

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
harbor.host=harbor.local
harbor.project=library
harbor.username=admin
harbor.password=Harbor12345

# ==================== Git 配置（可选）====================
gitlab.token=                     # 私有仓库 Token
git.proxy=                        # HTTP 代理，如 http://proxy:7890

# ==================== 离线流水线 ====================
local.registry=localhost:5000     # 本地镜像仓库
kaniko.image=registry.aliyuncs.com/kaniko-project/executor:latest
git.image=alpine:3.19
maven.image=maven:3.9-eclipse-temurin-17
loader.image=rancher/k3s:latest
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

## 许可证

MIT License
