# Nacos 接入收尾 —— 集群侧待办清单

> 背景：`springboot-app` CrashLoopBackOff 的根因是 `NACOS_ENABLED` 开关丢失（非镜像缺依赖，
> 镜像内 `nacos-client-2.3.2` + `spring-cloud-starter-alibaba-nacos-config-2023.0.1.0` 已验证存在）。
> 仓库侧修复已完成；以下为**必须在集群上执行/核对**的事项，本机 kubectl 指向未启动的 minikube，无法代办。

## 逐项核对状态

### 1. 集群镜像版本核对 —— ❌ 待用户执行

Pod 日志时间 08:02，本地 `k3s.tar` 构建于 15:37。若集群镜像更旧需先导入：

```bash
# 构建机（60.205.252.82）上执行
ctr -n k8s.io images import k3s.tar
# 或：docker load < k3s.tar && docker tag k3s:v1 docker.io/library/k3s:v1
```

镜像内容已验证（含全部 Nacos 依赖），**无需重新 build**。
核对方法：`kubectl get pod <pod> -o jsonpath='{.status.containerStatuses[0].imageID}'` 对比本地 `docker inspect k3s:v1 | jq '.[0].Id'`。

### 2. delivery-db Secret（数据源凭据）—— ❌ 待用户决策+执行

当前 `k3s-deploy2.yaml` 已无 `delivery-db` Secret 注入（前次改动删除，未恢复）。**二选一**：

- **方案 A（Secret 注入，安全性更好）**：恢复 Deployment env 中的 `secretKeyRef: delivery-db` 块（历史版本有完整写法，见 git 历史或本清单末尾模板），然后：
  ```bash
  kubectl create secret generic delivery-db \
    --from-literal=url='jdbc:mysql://<host>:3306/k3db?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shhanghai' \
    --from-literal=username=<user> --from-literal=password=<password>
  ```
  注意：URL 末尾的时区参数本清单模板里写作 `Asia/Shanghai`（以 `application.properties` 第 7 行为准）。

- **方案 B（Nacos 下发）**：按 `nacos-config-example.yaml` 第 12-16 行说明，把 `spring.datasource.*` 放进 Nacos 的 `k3s-demo.yaml`，凭据不进 Secret，直接覆盖应用内置默认。数据源从 Nacos 拉到后，`DB_URL` 环境变量失效（无害）。

> 不恢复任何一种 → `DB_URL` 落回默认 `127.0.0.1:3506/k3db` → 继续 Connection refused。这是当前崩溃的直接原因。

### 3. Nacos Service 名与端口核对 —— ⚠️ 仓库侧已改，集群侧待核对

`k3s-deploy2.yaml` 已改为 `nacos-svc.default.svc.cluster.local:8848`（对应 `k8s/nacos.yaml` 的 `metadata.name: nacos-svc`）。
需在集群上核对实际 Service 名一致：

```bash
kubectl get svc | grep nacos        # 期望: nacos-svc  NodePort  ...  8848:30848/TCP  9848:30948/TCP
kubectl run ncn --rm -it --image=busybox --restart=Never -- \
  nc -zv -w 3 nacos-svc.default.svc.cluster.local 8848   # 通则 Exit 0
```

若线上 Service 实际叫别的名字（例如就叫 `nacos`），以 `kubectl get svc` 实际名称为准回改 `NACOS_SERVER_ADDR`。

### 4. Nacos dataId / 鉴权核对 —— ❌ 待用户在控制台核对

- 控制台：`http://<节点IP>:30848/nacos`（**注意是 30848，不是附件里写的 30888**——以集群实际 NodePort 为准，`kubectl get svc nacos-svc` 可查）
- Data ID 必须为 **`k3s-demo.yaml`**（YAML 格式，非 properties），Group=`DEFAULT_GROUP`，命名空间=public
- 若 Nacos 开了鉴权（`NACOS_AUTH_ENABLE=true`）：`nacos-auth` Secret 的 username/password 必须是 **Nacos 控制台用户**（不是 nacos-mysql DB 密码）：
  ```bash
  kubectl get secret nacos-auth -o jsonpath='{.data.username}' | base64 -d; echo
  ```
  确认输出是 Nacos 用户名而非 DB 用户。
- 若走上面方案 B，dataId 里还需包含可达的 `spring.datasource.url`（Nacos 自连的阿里云 RDS 可复用）。

### 5. 应用与滚动更新 —— ❌ 待用户执行

```bash
kubectl apply -f k3s-deploy2.yaml
kubectl rollout restart deployment/springboot-app
kubectl rollout status deployment/springboot-app   # 旧 Pod 随新 Pod Ready 自动退场
```

成功判据：新 Pod 日志出现 `Located property source: [BootstrapPropertySource {name='bootstrapProperties-k3s-demo.yaml'...}]` 且无 401/403。

## Secret 注入模板（方案 A 用，恢复到 Deployment env 段）

```yaml
- name: DB_URL
  valueFrom:
    secretKeyRef: {name: delivery-db, key: url}
- name: DB_USERNAME
    secretKeyRef: {name: delivery-db, key: username}
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef: {name: delivery-db, key:密码对应 key}
```

## 仓库侧已完成（无需重复）

- `k3s-deploy2.yaml`：补 `NACOS_ENABLED=true`、`NACOS_SERVER_ADDR=nacos-svc...:8848`、`NACOS_NAMESPACE=""`
- `bootstrap.properties`：补 `username`/`password` 映射
- 新增 `BootstrapPropertiesMappingTest`（3/3 绿），锁 env↔bootstrap 映射契约
