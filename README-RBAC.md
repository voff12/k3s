# K3s Demo 应用 RBAC 配置说明

## 问题描述 (Problem)

应用在尝试缩放部署时出现 403 Forbidden 错误：
```
User "system:serviceaccount:default:default" cannot update resource "deployments/scale" 
in API group "apps" in the namespace "default"
```

**中文说明：** 应用使用的服务账户 `system:serviceaccount:default:default` 缺少必要的 Kubernetes 权限，无法执行部署缩放、Pod 执行等操作。

## 解决方案 (Solution)

应用 `k8s-rbac.yaml` 中定义的 RBAC 资源来授予必要的权限。

**中文说明：** 通过创建 Role/ClusterRole 和 RoleBinding/ClusterRoleBinding 来为服务账户授予所需的权限。

## 快速修复 (Quick Fix)

### 选项 1：为默认服务账户应用 RBAC（推荐）

```bash
kubectl apply -f k8s-rbac.yaml
```

**这将创建：**
- **ClusterRole**：授予集群级资源的只读权限（节点、持久卷、事件）以及跨所有命名空间的只读权限
- **Role**：授予 default 命名空间的写权限（pods、deployments、replicasets 和 **deployments/scale**）
- **ClusterRoleBinding**：将集群级只读权限授予 `default` 服务账户
- **RoleBinding**：将 default 命名空间的写权限授予 `default` 服务账户

**中文说明：** 这是最简单的配置方式，直接为默认服务账户授予权限，无需修改部署配置。

### 选项 2：使用专用服务账户

如果您希望使用专用的服务账户：

1. 取消注释 `k8s-rbac.yaml` 中的第二个 RoleBinding
2. 更新您的部署以使用该服务账户：
   ```yaml
   spec:
     serviceAccountName: k3s-demo-sa
   ```

**中文说明：** 这种方式更安全，使用专用的服务账户而不是默认账户，便于权限管理和审计。

## 验证配置 (Verify)

应用配置后，验证权限是否正确：

```bash
# 检查 RoleBinding 是否存在
kubectl get rolebinding k3s-demo-rolebinding -n default

# 检查 Role 是否存在
kubectl get role k3s-demo-role -n default

# 测试缩放功能（现在应该可以工作了）
kubectl scale deployment nginx --replicas=2 -n default
```

**中文说明：** 执行上述命令确认 RBAC 资源已正确创建，并测试部署缩放功能是否正常工作。

## 所需权限 (Required Permissions)

### ClusterRole（集群级只读权限）

- **Nodes（节点）**: get, list, watch - 用于仪表板和存储视图
- **PersistentVolumes（持久卷）**: get, list, watch - 用于存储视图
- **Events（事件）**: get, list, watch - 用于仪表板
- **Pods（所有命名空间）**: get, list, watch - 用于仪表板
- **Deployments（所有命名空间）**: get, list, watch - 用于仪表板
- **ReplicaSets（所有命名空间）**: get, list, watch

**中文说明：** ClusterRole 提供集群级别的只读权限，允许应用查看所有命名空间的资源，但不允许修改。

### Role（Default 命名空间写权限）

- **Pods**: get, list, watch, create, update, patch, delete - Pod 的完整操作权限
- **Pods/exec**: create, get - **终端/执行功能的关键权限！**
- **Deployments**: get, list, watch, create, update, patch, delete - 部署的完整操作权限
- **Deployments/scale**: get, update, patch - **缩放功能的关键权限！这修复了您的错误！**
- **ReplicaSets**: get, list, watch - 用于从 Pod 查找对应的部署

**中文说明：** Role 提供 default 命名空间的写权限，允许应用创建、更新、删除 Pod 和部署，以及执行终端连接和部署缩放操作。

## 故障排查 (Troubleshooting)

如果仍然遇到权限错误，请按以下步骤排查：

### 1. 验证服务账户名称

检查部署使用的服务账户名称是否匹配：

```bash
kubectl get deployment <your-deployment-name> -o jsonpath='{.spec.template.spec.serviceAccountName}'
```

**中文说明：** 确认部署使用的服务账户名称。如果返回空值，说明使用的是默认服务账户 `default`。

### 2. 检查 RoleBinding 配置

查看 RoleBinding 的详细信息，确认服务账户是否正确绑定：

```bash
kubectl describe rolebinding k3s-demo-rolebinding -n default
```

**中文说明：** 检查 RoleBinding 的 `subjects` 部分，确认服务账户名称和命名空间是否正确。

### 3. 验证 Role 权限

查看 Role 的详细权限配置：

```bash
kubectl describe role k3s-demo-role -n default
```

**中文说明：** 确认 Role 中包含了所有必需的权限，特别是 `pods/exec` 和 `deployments/scale`。

### 4. 常见问题

**问题：终端连接失败，提示权限不足**
- **解决：** 确认 Role 中包含了 `pods/exec` 权限，并重新应用 RBAC 配置

**问题：部署缩放失败**
- **解决：** 确认 Role 中包含了 `deployments/scale` 权限

**问题：无法查看其他命名空间的资源**
- **解决：** 确认 ClusterRoleBinding 已正确创建并绑定到服务账户

### 5. 重新应用配置

如果修改了 RBAC 配置，需要重新应用：

```bash
kubectl delete -f k8s-rbac.yaml
kubectl apply -f k8s-rbac.yaml
```

**中文说明：** 删除并重新创建 RBAC 资源，确保配置生效。注意：删除操作不会影响正在运行的 Pod，但新创建的 Pod 会使用新的权限配置。
