
# CLAUDE.md

> **架构规约请见 [ARCHITECTURE.md](./ARCHITECTURE.md)** —— 包结构 / 命名 / 响应模型 / 时间字段 / Spec 契约等强制约定。
> 本文档（CLAUDE.md）描述**协作行为**；架构与契约约束由 ARCHITECTURE.md 维护，由 ArchUnit 测试机器强制执行。

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

## 5. 测试先行（Test First）

**有测试覆盖才算完成，示例用法不等于测试。**

- 新增功能必须附带对应测试，不允许只写"示例用法"代替测试。
- 修复 bug 前，先写能稳定复现该 bug 的测试，再修复，再确认测试通过。
- 不得删除或注释掉已有测试；如确实需要，需在 PR 中明确说明原因。

## 6. 明确的依赖管理（Explicit Dependencies）

**不引入未经授权的依赖，版本必须锁定。**

- 不得引入新的第三方库，除非用户明确要求。
- 优先使用项目已有库的现成功能，不重复手写等价实现。
- 所有新增依赖的版本号必须明确固定（如 `1.2.3`），禁止使用 `*` 或 `latest`。

## 7. 安全与隐私（Security & Privacy）

**防止凭据泄漏和不可信输入。**

- 不得将密钥、密码、token、证书等硬编码在源文件中；使用环境变量或配置文件。
- 不得向日志输出敏感信息（用户数据、凭证、PII）。
- 所有来自外部的输入（HTTP 请求、文件、CLI 参数）必须做合法性校验后再使用。

## 8. 可读性规范（Readability）

**代码写给人读，只是顺带给机器执行。**

- 单个函数/方法不超过 40 行；超过时必须拆分为更小的命名函数。
- 禁止"聪明代码"（clever code）：优先选择最直白、最容易理解的写法。
- 变量名必须传达含义；禁止无意义的单字母变量（循环计数器 `i/j/k` 除外）。

---

## 开始工作前的 Checklist

**每次开始任务前，必须完成以下四步，然后再动手写代码：**

1. **复述理解** — 用自己的话重述任务，明确说出"我理解你要我做的是……"；若理解有误，等用户确认后再继续。
2. **影响范围分析** — 列出本次改动会涉及哪些文件/模块/接口，说明改动边界。
3. **方案对比** — 给出"最简方案"与"完整方案"各自的优缺点，让用户选择，不要擅自选择复杂方案。
4. **完成后自查** — 对照原始需求，逐条列出：① 做了什么 ② 刻意没做什么 ③ 哪些地方不确定。

---

## AI 编程提问模板

每次向 AI 提交编码任务时，在提示词末尾附加以下三问，要求 AI 在回答末尾逐项作答：

> **完成后请列出：**
> ① 你做了什么改动
> ② 你没有做什么（刻意跳过的）
> ③ 你对哪里不确定

这三问能暴露大部分 AI 的"自作主张"行为，是最低成本的质量检查手段。

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
