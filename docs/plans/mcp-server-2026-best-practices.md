# MCP Server 2026 最佳实践调研报告

调研日期：2026-10-23。仅采信 2025 下半年至 2026 年来源，老内容仅作背景。
应用场景：Spring Boot 3.2 / Java 17 K8s 发布平台，将「创建应用→登记制品→创建发布→触发执行→轮询状态」异步长任务暴露为 MCP Server 工具（倾向 Spring AI MCP Server Boot Starter，webmvc，STREAMABLE 协议）。

---

## 1. MCP 规范最新版本（2026-07-28）关键变化

### 官方规范事实（modelcontextprotocol.io / Cloudflare 对新规范的解读）

最新规范版本为 **2026-07-28（latest）**，相对 2025 系列是一次大的协议重构：

1. **彻底无状态化（最大变化）**
   - 移除了 `initialize`/`initialized` 握手和协议级会话（`Mcp-Session-Id` 头被移除）。
   - 每个请求自行携带协议版本（`MCP-Protocol-Version`）、客户端身份与能力；可选的 `server/discover` 用于了解服务器。
   - 服务器可运行在纯请求级无状态基础设施上，不再需要粘性会话/流保持/消息重放。
   - 来源：Cloudflare 博客《The next generation of MCP》（2026-08-06）；getmaxim.ai 文章亦印证「removed the initialize handshake and protocol-level sessions, moved version and capabilities into per-request _meta」。

2. **强制新 HTTP 头**
   - Streamable HTTP 请求**必须**携带 `MCP-Protocol-Version`、`Mcp-Method`（如 `tools/call`）、`Mcp-Name`（如工具名）。
   - 意义：网关/WAF/限流器无需解析 JSON body 即可做路由、限流、工具级指标。
   - 来源：Cloudflare 博客（2026-08-06）。

3. **SSE 传输**
   - 旧 HTTP+SSE 传输在 2025-03-26 已被 Streamable HTTP 取代；2026-07-28 将其正式标记为 **deprecated**（非立即移除）。
   - 新规范引入正式功能生命周期（Active/Deprecated/Removed），deprecated 功能须保留至少 12 个月才可移除，现有实现有迁移窗口。
   - 同期被 deprecated 的还有：**Roots、Sampling、Logging、Dynamic Client Registration (DCR)**。DCR 计划 2027 年夏后移除，授权优先预注册客户端，其次 CIMD（Client ID Metadata Documents）。
   - 来源：Cloudflare 博客（2026-08-06）。

4. **Elicitation 重做（破坏性变更）**
   - 旧版 `elicitation/create` 依赖保持开放的流；新版改为 **Multi Round-Trip Requests (MRTR)**：服务器对 `tools/call` 返回 `InputRequiredResult`（`resultType: "input_required"`，含 `inputRequests`），客户端收集输入后携带 `inputResponses`（及 `requestState`）**重试**同一操作（JSON-RPC id 必须不同）。
   - 来源：modelcontextprotocol.io 2026-07-28 tools 规范页；Cloudflare 博客。

5. **工具定义新特性（2026-07-28 tools 规范页）**
   - **命名规则**：1–128 字符，大小写敏感，仅允许 `A-Z a-z 0-9 _ - .`；服务器内唯一；聚合多服务器的客户端应用前缀消歧（`serverInfo.name` 不保证唯一，不可用于消歧）。合法示例：`getUser`、`DATA_EXPORT_v2`、`admin.tools.list`。
   - **outputSchema**：可选 JSON Schema（默认 2020-12）；提供后服务器 MUST 通过 `structuredContent` 返回符合 schema 的结构化结果，且 SHOULD 同时在 TextContent 块返回序列化 JSON（向后兼容）。
   - **annotations**：可选属性描述工具行为；客户端 MUST 视为不可信（除非可信服务器）。注：规范页未再列 readOnlyHint/destructiveHint 等具体字段（早期版本的 hint 字段在最新页未见展开，待查 2026-07-28 schema 全文确认）。
   - **x-mcp-header**：可将原始类型参数（integer/string/boolean）镜像为 `Mcp-Param-{name}` HTTP 头；不得标记敏感参数。
   - **错误处理两层模型**：协议错误用 JSON-RPC error；工具执行错误用结果内 `isError: true`，客户端 SHOULD 把执行错误交给 LLM 自我纠正。
   - **有状态工具的非规范指导**：协议无会话，跨调用关联应通过显式 handle（如 `basket_id`）——正好契合发布平台的「发布单 ID」模式。
   - **缓存提示**：`tools/list` 等结果新增 `ttlMs`/`cacheScope`；工具目录应确定性排序。
   - **能力协商变化**：`listChanged` 通知改经 `subscriptions/listen` 流订阅（`toolsListChanged: true`）。

6. **扩展框架**
   - Tasks（长任务）、MCP Apps、Enterprise-Managed Authorization 已移入扩展（extension）机制。
   - 来源：Cloudflare 博客；tasks.extensions.modelcontextprotocol.io。

7. **授权强化**（见第 3 节）。

### 对本项目的含义
- STREAMABLE 已是事实标准且是唯一应选的新建传输；SSE 不应再用于新实现。
- 协议已无会话概念，「触发-返回 ID-轮询」与规范的有状态工具指导完全一致。
- 注意：Spring AI 2.0 当前对齐的是 **2025-11-25** 规范（MCP Java SDK 2.0.0），2026-07-28 规范对应 MCP Java SDK 2.0.x 的支持在 spring-projects/spring-ai#6954 中仍在讨论（截至调研时未落地）。即：Java 生态暂时跑在「无状态前的最后一代」协议上，新握手移除等变化尚未进入 Spring AI。

---

## 2. 2026 社区共识的 MCP Server 设计最佳实践

### 官方规范事实
- 工具命名/唯一性/outputSchema/错误两层模型：见第 1 节第 5 点（官方规范）。
- inputSchema 必须为合法 JSON Schema；无参工具推荐 `{"type":"object","additionalProperties":false}`。

### 异步长任务的官方推荐模式（Tasks 扩展，2026-07-28）
来源：tasks.extensions.modelcontextprotocol.io（官方扩展页，2026-07-28 稳定版 schema，提案 SEP-2663）。

- 定位：专为「工具调用阻塞不切实际」的场景设计——CI 流水线/批任务（数分钟到数小时）、human-in-the-loop 审批、外部作业系统（**云部署、排队任务、自带作业 ID 的异步 API**——与发布平台完全同型）、不可靠连接。
- 模式：**服务端返回持久任务句柄而非阻塞**。
  1. 能力协商：客户端在 `_meta` 声明 `io.modelcontextprotocol/tasks` 扩展；未声明的客户端则按常规阻塞返回或收到 `-32021` 错误（渐进增强）。
  2. `tools/call` 返回 `CreateTaskResult`（`resultType:"task"`，含 `taskId`、初始状态、`ttlMs`、`pollIntervalMs`）；任务响应前已持久化到 Task Store。
  3. 客户端用 `tasks/get` 按 `pollIntervalMs` 轮询；状态机：`working → (input_required) → completed / failed / cancelled`。
  4. 中途输入：`input_required` 时经 `tasks/update` 补充输入。
  5. 服务端推送可选：`notifications/tasks`（客户端经 `subscriptions/listen` 订阅），取消经 `tasks/cancel`（协作式）。
  6. 完成时 `result` 的内容 = 同步执行本应返回的内容；失败时 `error` 为 JSON-RPC error。
- **触发-轮询是官方推荐的第一模式**，服务端通知只是可选增强。

### 社区观点（2026）
- Phil Schmid《MCP is Not the Problem, It's your Server》（2025 下半年高引文章）：服务器失败的根因多在设计而非协议；要点包括工具少而精、描述写给模型看、响应即提示机会、与 Skills 互补。
- AWS 机器学习博客《MCP tool design: Practical approaches and tradeoffs》（2026）：歧义命名是工具选择失败主因；用 context engineering 思路设计工具面。
- Speakeasy / itential 等 2026 年文章共识：outcome-first 工具（按业务结果而非 CRUD 包装 API）、控制工具数量（curated surface area）、减少 LLM 混淆与上下文占用。
- thenewstack《15 Best Practices for Building MCP Servers in Production》（2026）：将每个 server 视为 bounded context、无状态+幂等工具设计、实现 cancellation、拥抱 streamable/stateless。（注：正文抓取被订阅弹窗遮挡，仅标题级要点可确认。）

### 对本项目的落地建议（综合以上）
- 工具设计为「结果导向」：`create_release`、`trigger_release_execution`、`get_release_status(release_id)` 而非暴露底层 CRUD；发布单 ID 即显式 handle。
- 长任务首选触发-轮询（返回 release/execution ID + 建议轮询间隔 + TTL），与 MCP Tasks 扩展同构；若后续 SDK 支持可直接映射到 `CreateTaskResult`。
- 每个写工具配 `outputSchema` + `structuredContent`（含 status/message/releaseId），错误用 `isError:true` + 可读 message 让 LLM 自我纠正。
- 幂等：创建类工具支持客户端幂等键；触发执行对同一 release 重复调用应返回现状而非重复执行。

---

## 3. 安全最佳实践最新进展

### 官方规范事实
- HTTP 传输的授权框架基于 **OAuth 2.1，MCP 服务器是 Resource Server（资源服务器）而非授权服务器**：
  - Protected Resource Metadata（RFC 9728，`/.well-known/oauth-protected-resource`）+ `WWW-Authenticate` 挑战；
  - 客户端带 PKCE 的授权码流程；`resource` indicator（RFC 8707）指向具体 MCP 服务器；服务器必须校验 token audience 是自己（audience-bound tokens）；
  - 2026-07-28 新增：RFC 9207 签发方识别（`iss` 参数）；**禁止 token passthrough**（不得接受为其他服务签发的令牌转发下游）；禁止把会话 ID 当认证。
  - 来源：corgea《MCP Security Best Practices: 2026 Checklist》（2025-06 首发，2026-08-25 更新）；Cloudflare 博客。
- Spring AI 官方文档事实：HTTP 类传输的 starter **默认暴露未认证端点，自身不提供任何认证授权**；生产部署必须自行叠加 Spring Security 或社区项目 spring-ai-community/mcp-security（提供 OAuth 2.0 / API-key 安全）。STDIO 除外。

### 自定义 API Key 是否仍可行（2026 观点）
- 社区共识：**优先 OAuth 而非静态密钥**；但在 Spring 生态中 API-key 仍是官方社区项目（mcp-security）支持的一等方案，适合内部平台。
- 若用密钥：短期、最小权限、存环境变量/密钥管理器、绝不写入 MCP JSON 配置文件（最常见泄漏点）；云上用工作负载身份。
- 对本项目（企业内部发布平台）：OAuth Resource Server 是正解；若短期落地，自定义 API Key + Spring Security 过滤器是可接受过渡，但需做到最小权限+可轮换+不进配置文件/日志。

### 已知漏洞类别与缓解（2026 清单，corgea 2026-08-25 更新版）
1. **Tool Poisoning**：恶意服务器在工具名/描述/schema 中嵌入隐藏指令；变种：cross-server shadowing、schema 投毒、rug pull（安装后改描述）。缓解：安装时展示完整描述逐工具审批、定义快照哈希变更告警、写操作人工确认。
2. **间接 Prompt Injection（经工具结果）**：工具返回的外部数据进入模型上下文携带指令。缓解：外部内容加分隔符标注「数据非指令」、视外部 MCP server 为不可信管道、human-in-the-loop 兜底。
3. **授权缺陷**：Confused Deputy / token passthrough / 会话滥用 / 无认证（CVE-2025-49596 MCP Inspector localhost 无认证 + DNS rebinding → RCE）。
4. **权限过大**：通用 `run_sql`/`run_shell` 类工具放大注入破坏。缓解：窄化工具（`get_customer(id)` 而非 `run_sql`）、默认只读、写操作显式开启、最小权限凭据、限制每会话工具数。
5. **供应链**：伪装官方包（2025-09 Postmark MCP npm 包 BCC 邮件事件）、typosquatting。缓解：固定精确版本/commit hash、禁自动更新、白名单。
6. **配置文件明文密钥**：claude_desktop_config.json / .cursor/mcp.json 明文 key 被提交仓库。
7. **实现层漏洞**：命令注入（CVE-2025-6514 mcp-remote）、路径遍历、SSRF（阻断云元数据地址）、非 root 容器运行、限速限时。
- OWASP Agentic Top 10 (2026) 将 tool poisoning 归入 ASI01（间接注入），CSA 有对应指南（labs.cloudsecurityalliance.org）。

### 对本项目的含义
- 描述字段本身也是攻击面：不要在 tool description 里放敏感信息；作为**服务端**，主要防的是「被注入的发布参数」——所有入参做 schema 校验+业务校验，执行类操作要求显式确认语义（可在描述中声明需要用户确认）。
- 端点默认裸奔：上线前必须加 Spring Security（OAuth Resource Server 或 API Key 过滤器）。
- 工具窄化：暴露 `deploy_release(id)` 而非「任意 kubectl 执行」。

---

## 4. Spring AI 2026 对 MCP Server 的支持现状

### 官方文档事实（docs.spring.io，对应 Spring AI 2.0.2 稳定版文档）
- 最新稳定版：**2.0.2**；另有 1.1.8 / 1.0.9（1.x 稳定线）、2.1.0-RC1（预览）、2.0.3/2.1.0-SNAPSHOT。
- 支持四种协议（`spring.ai.mcp.server.protocol`）：STDIO、**SSE（2.0.0 起废弃，建议 STREAMABLE 替代）**、**STREAMABLE（取代 SSE）**、**STATELESS**（无状态，适合微服务/云原生，代价是失去双向通信）。WebMVC 与 WebFlux starter 均有。
- 注解 API（2.0 起 mcp-annotations 模块从社区并入正式）：
  - `@McpTool`（+`@McpToolParam` 自动生成 JSON Schema）、`@McpResource`、`@McpPrompt`、`@McpComplete`；
  - 特殊参数注入：`McpMeta`、`@McpProgressToken`、`McpSyncServerExchange`/`McpAsyncServerExchange`、`McpTransportContext`、`CallToolRequest`；2.0 统一为 `McpSyncRequestContext`/`McpAsyncRequestContext`（logging/progress/sampling/elicitation 统一入口）。
  - `spring.ai.mcp.server.annotation-scanner.enabled=true`。
- 同步/异步：`spring.ai.mcp.server.type=SYNC`（默认，`McpSyncServer`）/ `ASYNC`（`McpAsyncServer`，Project Reactor）；两类注解方法互斥注册。
- 服务器能力：Tools/Resources/Prompts/Completions/Logging/**Progress**/Ping 默认全开，可逐项禁用。
- 安全：starter 不提供认证（见第 3 节）；可用 `TransportContextExtractor` 把 HTTP Authorization 头注入 `McpTransportContext` 供工具方法读取。

### 官方博客事实（spring.io，2026-06-12，Spring AI 2.0.0 GA）
- Spring AI 2.0 **设计基线为 Spring Boot 4.0/4.1 + Spring Framework 7.0**，并升级到 Jackson 3、全面 JSpecify null-safety。
- 随附 **MCP Java SDK 2.0.0，对齐 2025-11-25 MCP 规范**；Streamable HTTP 成为默认传输；WebMVC/WebFlux transport 实现从 MCP Java SDK 移入 Spring AI。
- ⚠️ **关键兼容性结论：Spring AI 2.x 与 Spring Boot 3.2 不兼容**（基线是 Boot 4.x / Framework 7）。停留在 Spring Boot 3.2 的项目应使用 **Spring AI 1.x**（1.1.x 线支持 Boot 3.x，其 MCP server starter 同样提供 STREAMABLE）；且 Boot 3.5 已于 2026-06-30 EOL，3.2 更早EOL——长期看需要规划升级 Boot 4 + Spring AI 2。
- MCP Java SDK 对 2026-07-28 规范的支持（SDK 2.0.x）见 spring-projects/spring-ai#6954，截至调研时仍在讨论、未发布。

### 对本项目的选型结论
- 现状组合：**Spring Boot 3.2 + Spring AI 1.1.x + `spring-ai-starter-mcp-server-webmvc` + `protocol=STREAMABLE`** 是可落地路径，但 3.2 已 EOL，存在安全维护风险。
- 推荐路径：借此次改造升级到 **Spring Boot 4.x + Spring AI 2.0.x**，获得官方维护的 MCP SDK、注解 API、STREAMABLE/STATELESS 与未来的 2026-07-28 规范支持。
- 异步长任务：Spring AI 注解通过 `@McpProgressToken` 支持协议内 progress 上报；但「发布执行」级长任务建议采用业务层触发-轮询（任务 ID），与 MCP Tasks 扩展同构，等待 SDK 支持后平滑迁移。

---

## 未找到 2026 年新信息的点
- 2026-07-28 规范中 tool annotations 的 readOnlyHint/destructiveHint 等具体字段是否保留：tools 规范页未展开，未进一步确认（建议查 2026-07-28 完整 schema）。
- MCP Tasks 与原生 progress notification 的官方关系说明：Tasks 官方页未提及。
- Spring AI 1.1.x 对 Boot 3.2 的明确支持矩阵官方表述：未找到逐版本对照表，结论基于「2.0 基线 Boot 4」反推 + 1.1.8 仍在维护线。

## Sources（实际读取过的页面）
- [MCP Specification 2026-07-28 — Server: Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
- [Cloudflare — The next generation of MCP（2026-08-06）](https://blog.cloudflare.com/mcp-v2)
- [Spring AI Reference — MCP Server Boot Starter（2.0.2 文档）](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [Spring Blog — Spring AI 2.0.0 GA Available Now（2026-06-12）](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now)
- [MCP Tasks Extension — Overview（2026-07-28 稳定 schema）](https://tasks.extensions.modelcontextprotocol.io/)
- [Corgea — MCP Security Best Practices: 2026 Checklist（2026-08-25 更新）](https://corgea.com/learn/mcp-security-best-practices)
- [spring-projects/spring-ai#6954 — Support MCP Java SDK 2.0.x (MCP 2026-07-28)](https://github.com/spring-projects/spring-ai/issues/6954)（仅搜索结果摘要，未读正文）
