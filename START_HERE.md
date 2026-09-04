# 上岸仓库工作入口

## 当前状态

本仓库已经进入 V1.x.x 开发与维护阶段，不是等待从 Task 1 开始的空仓库交接包。

当前活动开发线默认为 V1.x.x。V1.x.x 固定现有需求与核心业务逻辑，只完成已经批准的 V1 Task、Bug 修复和不改变业务语义的 UI 调整。V2/V3 仅有方向性路线图，尚未获得实施授权。

## 必读顺序

修改代码前必须依次完整阅读：

1. `AGENTS.md`
2. `docs/specs/2026-08-27-shangan-v1-design.md`
3. `docs/plans/2026-08-27-shangan-v1-implementation-plan.md`
4. `docs/traceability/2026-08-27-shangan-v1-traceability.md`
5. `docs/roadmap/2026-09-04-shangan-version-roadmap.md`
6. 与当前 Task 相关的 ADR

发生冲突、文档状态不一致或聊天要求超出当前冻结范围时，停止实现并向维护者说明具体冲突。不得自行选择一份更方便实现的旧文档。

## 文档职责

```text
AGENTS.md
  └─ 全局安全、工程与范围约束

当前版本 Spec + ADR
  └─ 已冻结的产品规则、架构和关键决策

当前版本 Implementation Plan
  └─ 获准执行的 Task、文件、接口、测试和提交边界

当前版本 Traceability
  └─ 需求、实现、自动化验证和最终验收映射

Version Roadmap
  └─ 未来方向与版本门禁，不是实现要求
```

聊天、原型、路线图和常见产品做法都只能作为设计输入，不能替代当前版本的冻结文档。

## 版本方向

- V1.x.x：维持现有学习监督闭环，只修 Bug 和调整 UI，不新增主能力。
- V2.x.x：规划面向学习者的 AI 助教，在 V1「毛线团团」基础上升级学习伙伴交互，并由服务端向 Server酱投递用户配置的日报/周报。
- V3.x.x：规划全 App 体验优化、服务端性能与多实例/多集群部署、主题和背景等外观个性化、课程仅音频播放、画中画，以及后台上传 PDF 并加入作战单的资料阅读闭环。

完整边界见 `docs/roadmap/2026-09-04-shangan-version-roadmap.md`。

## 执行要求

1. 先检查 Git 状态，保护用户已有修改。
2. 确认用户请求属于当前活动版本，并能映射到冻结需求和获批 Task。
3. 只处理当前 Task，不为 V2/V3 预建代码、依赖、迁移或基础设施。
4. 按 `AGENTS.md` 要求先写失败测试、运行窄测试、格式化并审查范围。
5. 涉及 API、启动边界、数据库迁移或配置时，执行对应文档要求的额外验证。

如果用户希望启动 V2 或 V3，应先创建并批准对应版本的 Spec、ADR、Implementation Plan 和 Traceability，而不是直接编码。
