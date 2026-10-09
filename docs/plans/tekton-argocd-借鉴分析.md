# Tekton（CI）+ Argo CD（CD）对当前 K3s 平台的借鉴分析

> 日期：2026-10-09
> 背景：当前平台是 Spring Boot 自研 CI/CD（DevOpsService / ReleaseService），在 Java 进程内编排 K8s Job 完成 clone → BuildKit 构建 → 镜像导入 → 部署 → 预览环境。docs/全流程自动化发布与AI监控技术方案-2026.md 已规划 Argo CD 做 GitOps，但未评估 Tekton。

## 1. 结论

**不建议整体替换为 Tekton + Argo CD，建议"借鉴机制、按需引入 Argo CD"。**

- 当前平台的痛点（流水线状态在 Java 内存、平台持有集群写权限、部署不可声明式回滚）里，**最值得借鉴的是 Argo CD 的 GitOps 模式**，且与已有技术方案一致。
- Tekton 解决的问题（构建步骤容器化、声明式流水线）当前平台**已经用自己的方式解决了**（K8s Job + BuildKit + PVC 缓存 + SSE 日志），引入 Tekton 是平移而非增量，短期收益低。
- 二者都是 CNCF 毕业项目、K8s 原生 CRD，若未来流水线复杂度上升（多语言、多步骤编排、重试矩阵），Tekton 是自然演进方向。

## 2. 三方对照

| 维度 | 当前平台（DevOpsService/ReleaseService） | Tekton | Argo CD |
|---|---|---|---|
| 定位 | CI + CD 一体（自研门户） | 纯 CI（构建/测试框架） | 纯 CD（同步 Git → 集群） |
| 流水线定义 | Java 代码硬编码（PipelineConfig DTO） | CRD：Task/Step/Pipeline/PipelineRun，YAML 声明式、可版本化 | 不定义流水线，只声明期望状态（Application CRD） |
| 执行模型 | executor.submit 线程池 + K8s Job，状态在内存（DB 恢复） | 每步一个容器、Pod 执行，状态在 CRD status（etcd 持久） | 控制器循环 diff Git vs 集群，drift 自动/手动 sync |
| 崩溃恢复 | 进程重启→僵尸任务标 FAILED | PipelineRun 状态天然持久在集群，k8s 重调度 | 控制器自愈，Git 即真相 |
| 权限模型 | 平台 ServiceAccount 直接改 Deployment（写权限集中） | 每个 Task 可绑定独立 SA（step 级最小权限） | 平台只需 Git 读 + Argo 的 SA；应用凭据按 namespace 下发 |
| 回滚 | 依赖重新触发流水线 | 不负责 | `argocd app rollback` / git revert，秒级回退到任意历史 |
| 日志 | SSE 推送到页面（自研，体验好） | kubectl logs / CLI 查询，需自建 UI 或接 Tekton Dashboard | 同步事件/差异在 UI 可视化 |
| 多环境晋升 | preview namespace（自研多分支合并预览，是亮点） | 不负责 | ApplicationSet 生成多环境 Application；环境晋升是社区公认短板（Kargo/GitOps Promoter 兴起） |

## 3. 值得借鉴的具体机制

### 3.1 Tekton 的借鉴点（机制层面，不必引入产品）

1. **状态外置到集群而不是进程内存**。当前 `PipelineRun` 状态存 Java 内存 + 启动恢复，进程重启正在跑的任务即标 FAILED。Tekton 把每个 run 的状态写在 CRD status 里，控制器崩溃后 Pod 照跑、状态不丢。借鉴：把流水线状态推进逻辑挪到 Job 的 annotation/label 或 CRD，Java 端只做展示层（watch 而非持有）。
2. **流水线即数据（Pipeline as Data）**。当前 5 个步骤（clone/merge/build/import/deploy）写死在 Java 里；Tekton 的 Task/Pipeline 是 YAML 资源，可被 resolver 远程引用、按仓库版本化。借鉴：把 PipelineConfig 升级为仓库内的流水线描述文件（哪怕简化 JSON），平台只做解释器，新项目不用改 Java 代码。
3. **Step 级最小权限**。Tekton 每个 Task 可用不同 ServiceAccount（clone 用 git 凭据 SA、构建只写 Harbor、部署才碰 Deployment）。当前所有步骤在同一个 Job/凭据域里。借鉴：至少把"构建"与"部署"的凭据拆开。
4. **可复用 Catalog**。Tekton Catalog 提供现成 Task（git-clone、build-push、scan）。借鉴：把 Kaniko/BuildKit 构建、Harbor 推送等沉淀为可复用的 Job 模板库，而不是每个 Service 内嵌 YAML 字符串。

### 3.2 Argo CD 的借鉴点（建议真正引入，与既有方案一致）

1. **生产环境 GitOps 化**：部署清单独立仓库，CI（当前平台）只更新 digest → Argo CD 同步到 K3s。平台不再持有生产写权限，发布可审计（git history 即发布记录）、可秒级回滚。这正是 docs 技术方案 §3.1 第 4 条的落地路径。
2. **Drift 检测**：Argo CD 持续 diff 实际状态 vs 期望状态。当前平台可先做轻量版：定时对比 Deployment 的 image digest 与 Git/Harbor 期望值，页面展示漂移（把 ReleaseService 的 RuntimeValidation 语义扩展）。
3. **ApplicationSet**：多分支预览环境（当前平台亮点）可用 ApplicationSet 的 Pull Request generator 声明式生成——PR 开→自动建 App，PR 关→自动回收，替代自研 sweepStalePreviewEnvs 定时清扫。
4. **Sync hooks（PreSync/PostSync）**：借鉴其"部署前后钩子"模型设计数据库迁移与冒烟验证的挂载点，而不是把验证硬编码在部署流程里。
5. **健康状态分析**：Argo CD 对 Deployment/Job 等内建健康判定，可作为发布门禁口径的参考实现。

### 3.3 引入顺序建议（最小代价路径）

1. **第一步（无新组件）**：借 Tekton 思想做"状态外置 + 流水线即数据"——流水线描述进仓库、状态靠 watch K8s 资源，消除内存态。
2. **第二步（加一个组件）**：k3s 装 Argo CD（单副本，资源占用小），生产 Deployment 交 GitOps；平台保留预览环境 + 审批 + 日志门户定位。与既有技术方案 §3.1 一致。
3. **第三步（可选）**：预览环境迁移到 ApplicationSet PR generator；若未来需要多语言/复杂编排再评估 Tekton 替换构建段。

## 4. 风险与不确定性

- Argo CD 环境晋升（preview→beta→prod）无标准方案，若平台要统一管理晋升需自研或引入 Kargo，复杂度不低。
- Tekton 替换构建段会废弃已调通的 BuildKit cache mount / workspace PVC 增量 clone 优化（见 .tsien/memory 相关决策），迁移成本实打实。
- 联网检索仅确认了两项目的版本与特性概况（Tekton v1.9.0 LTS、Argo CD v3.4.x），未做本机 k3s 实测安装验证。
