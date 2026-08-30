# Codex 启动指令

你正在一个空仓库中实现“上岸”V1。先不要自行扩展产品，也不要一次性生成全部代码。

## 必读资料

按顺序完整阅读：

1. `AGENTS.md`
2. `docs/specs/2026-08-27-shangan-v1-design.md`
3. `docs/plans/2026-08-27-shangan-v1-implementation-plan.md`
4. `docs/traceability/2026-08-27-shangan-v1-traceability.md`

这些文件是唯一需求来源。聊天记录、常见教育 App 功能和“未来可能需要”都不能替代文档。

## 执行方式

1. 先检查当前仓库状态和工具链。
2. 建立唯一专属工作分支；需要隔离时创建对应 worktree。
3. 严格按实施计划 Task 1 → Task 16 顺序执行。
4. 当前先执行 **Task 1**，不要提前实现 Task 2。
5. 使用测试驱动：先写失败测试，再写最小实现，再运行验证。
6. Task 完成后运行该 Task 指定命令，检查 git diff，做一次需求与安全自审。
7. 每个 Task 只产生一个独立、可评审提交。
8. 汇报本 Task 的文件变化、测试命令、测试结果、已知风险和提交哈希，然后进入下一个 Task。
9. 从 Task 4 开始，每次提交前执行：

```bash
make format
make verify
```

## 不可违反的边界

- V1 只实现 iOS Flutter App；不要实现 Android、PC Web、macOS 或 Windows 客户端。
- 不引入 PostgreSQL、Redis、Kafka、微服务、Kubernetes、向量数据库或多智能体。
- Emby、LLM、ASR 和 MCP 凭据只能在服务端。
- AI 只有只读工具，不能修改计划、欠债、考试目标、学习记录或任何其他业务数据。
- 学习进度必须由上岸服务端校验；Emby 播放进度不是可信学习进度。
- 开摆必须与欠债事务联动；不提供撤销开摆。
- 不允许通过跳过测试、删除断言或降低业务规则使验证通过。
- 发现设计冲突时先停止当前 Task，给出具体冲突、影响和最小修正建议；不要私自改变产品边界。

现在开始：先输出你对 V1 边界、Task 1 交付物和验证方式的简短复述，然后实施 Task 1。
