# 将 Nacos 部署到 k3s 并读取 Qwen AppKey 配置指南

本文档说明如何：
1. 在 k3s 集群中部署 Nacos 配置中心
2. 在 Nacos 中写入 Qwen AppKey 配置
3. 让本项目（Spring Boot）从 Nacos 动态读取 `qwen.api.key`

---

## 一、前置条件

| 工具 | 版本要求 |
|------|---------|
| k3s  | v1.26+  |
| kubectl | 能连接到 k3s 集群 |
| Docker / 内网 Harbor | 用于存放镜像（离线环境） |

---

## 二、部署 Nacos 到 k3s

### 2.1 应用 YAML

项目已提供 `k8s/nacos.yaml`，包含：

- `PersistentVolumeClaim`：持久化 Nacos 内嵌 Derby 数据（1 Gi）
- `Deployment`：Nacos Server 单机模式（`nacos/nacos-server:v2.3.2`）
- `Service`（NodePort）：
  - `30848` → Nacos HTTP 控制台与配置 API
  - `30948` → Nacos gRPC（客户端连接）

```bash
kubectl apply -f k8s/nacos.yaml
```

### 2.2 等待 Nacos 就绪

```bash
kubectl rollout status deployment/nacos
# 输出: deployment "nacos" successfully rolled out
```

### 2.3 验证访问

```bash
# 获取节点 IP
NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
echo "Nacos Console: http://${NODE_IP}:30848/nacos"
```

打开浏览器访问上述地址，默认账号密码均为 `nacos`。

> **离线环境**：请先将 `nacos/nacos-server:v2.3.2` 推送到内网 Harbor，
> 并修改 `k8s/nacos.yaml` 中的 `image` 字段及 `imagePullPolicy: Never`。

---

## 三、在 Nacos 中写入 Qwen AppKey

### 3.1 通过控制台（推荐）

1. 登录 Nacos 控制台 → **配置管理** → **配置列表**
2. 点击右上角 **+** 新建配置：

   | 字段 | 值 |
   |------|----|
   | Data ID | `k3s-demo.properties` |
   | Group | `DEFAULT_GROUP` |
   | 配置格式 | `Properties` |
   | 配置内容 | 见下方 |

3. 配置内容：

   ```properties
   # Qwen (通义千问) AppKey
   qwen.api.key=sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

   # 可选：覆盖其他配置
   # qwen.api.model=qwen-max
   ```

4. 点击 **发布**。

### 3.2 通过 Nacos OpenAPI（脚本化）

```bash
NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')

curl -X POST "http://${NODE_IP}:30848/nacos/v1/cs/configs" \
  --data-urlencode "dataId=k3s-demo.properties" \
  --data-urlencode "group=DEFAULT_GROUP" \
  --data-urlencode "content=qwen.api.key=sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
```

---

## 四、Spring Boot 应用与 Nacos 的集成说明

### 4.1 依赖（已在 pom.xml 中添加）

```xml
<!-- Nacos Config -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
<!-- Bootstrap Context（Spring Cloud 3.x 需要显式引入）-->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bootstrap</artifactId>
</dependency>
```

### 4.2 Bootstrap 配置（`src/main/resources/bootstrap.properties`）

```properties
spring.application.name=k3s-demo

# Nacos 地址：集群内通过 Service DNS 访问
spring.cloud.nacos.config.server-addr=${NACOS_SERVER_ADDR:127.0.0.1:8848}
spring.cloud.nacos.config.namespace=${NACOS_NAMESPACE:}
spring.cloud.nacos.config.group=${NACOS_GROUP:DEFAULT_GROUP}
spring.cloud.nacos.config.file-extension=properties
spring.cloud.nacos.config.refresh-enabled=true
```

### 4.3 环境变量（已在 `k3s-deploy2.yaml` 中设置）

```yaml
env:
  - name: NACOS_SERVER_ADDR
    value: "nacos-svc.default.svc.cluster.local:8848"
  - name: NACOS_NAMESPACE
    value: ""
  - name: NACOS_GROUP
    value: "DEFAULT_GROUP"
```

### 4.4 动态刷新（`@RefreshScope`）

`QwenService` 已标注 `@RefreshScope`。当 Nacos 中的 `qwen.api.key` 更新后，
无需重启 Pod，Spring Cloud 会自动重新注入新值。

---

## 五、本地开发（不启动 Nacos）

在 `application.properties` 中直接填写 Key，`bootstrap.properties` 连接本地不存在的 Nacos 会打印警告但不影响启动：

```properties
qwen.api.key=sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

或者在启动时添加 JVM 参数禁用 Nacos：

```bash
-Dspring.cloud.nacos.config.enabled=false
```

---

## 六、配置刷新验证

在 Nacos 控制台修改 `qwen.api.key` 并发布后，约 10 秒内生效，可通过以下接口验证：

```bash
# 触发手动刷新（也可依赖自动刷新）
curl -X POST http://<节点IP>:30082/actuator/refresh

# 或观察 Pod 日志，Nacos 客户端会打印 "Refresh keys changed: [qwen.api.key]"
kubectl logs -f deployment/springboot-app
```

---

## 七、生产环境建议

| 项目 | 建议 |
|------|------|
| Nacos 存储 | 替换内嵌 Derby 为外部 MySQL（设置 `SPRING_DATASOURCE_*` 环境变量） |
| Nacos 集群 | 部署 3 节点集群保证高可用 |
| 鉴权 | 开启 `NACOS_AUTH_ENABLE=true` 并设置 `NACOS_AUTH_TOKEN_SECRET_KEY` |
| AppKey 安全 | 先将 `qwen.api.key` 存入 k3s Secret，再在 Nacos 配置中引用 `${secret_value}` |
| 命名空间隔离 | 为 dev / staging / prod 创建独立命名空间，避免配置混用 |
