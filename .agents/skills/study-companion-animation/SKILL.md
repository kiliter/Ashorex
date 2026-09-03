---
name: study-companion-animation
description: 上岸学习伙伴「毛线团团」的精灵表动作目录与接线规则。列出全部 25 个可播放动作（9 个标准状态 + 16 个朝向），以及当前 App 已接线的子集。Use when the user mentions 毛线团团、学习伙伴、宠物动作、精灵表、companion animation、poses、idle/挥手/跑步/朝向, or runs /study-companion-animation.
---

# 学习伙伴动作表

改学习伙伴动画前先读本文件，再改 `PetPose` 和 overlay，不要另造精灵表行。

## 素材

- 形象：毛线团团（`maoxian-tuantuan`），Codex v2
- 打包路径：`apps/ios/assets/pets/maoxian-tuantuan/spritesheet.webp`
- 清单：同目录 `pet.json`（`spriteVersionNumber: 2`）
- 表尺寸：`1536×2288`，8 列 × 11 行，每格 `192×208`
- 显示：`petDisplaySize = 96×104`
- 解码：`apps/ios/lib/features/companion/presentation/pet_sprite.dart`
- 行为：`apps/ios/lib/features/companion/presentation/study_companion_overlay.dart`

空格必须透明。按行裁切时只播该行动作占用的列，不要播后面的空格。

## 一共多少个动作

**25 个可点名动作**，铺在 **11 行** 里：

- 行 0–8：9 个标准状态（循环或一次性姿态）
- 行 9–10：16 个顺时针朝向（每格一个方向，不是循环跑）

`000` 是朝上，不是正面。正面/无方向回落到 `idle`。

## 标准状态（行 0–8）

占用列来自本仓库精灵表实测（不透明像素 > 40）。

| 行 | 英文 id | 中文 | 占用列 | 帧数 | 含义 | 上岸当前接线 |
|---:|---------|------|--------|-----:|------|-------------|
| 0 | `idle` | 待机 | 0–6 | 7 | 喘气、眨眼，低打扰循环 | 已接。`PetPose.idle` 目前只播 0–5 共 6 帧 |
| 1 | `running-right` | 向右跑 | 0–7 | 8 | 拖动向右的位移步态 | 已接 `runRight` |
| 2 | `running-left` | 向左跑 | 0–7 | 8 | 拖动向左的位移步态 | 已接 `runLeft` |
| 3 | `waving` | 挥手 | 0–3 | 4 | 打招呼，播完回待机 | 已接 `wave`（点击或从贴边探出） |
| 4 | `jumping` | 跳跃 | 0–4 | 5 | 起跳、顶点、落下、站稳 | 已接 `jumping`（往上拖，循环） |
| 5 | `failed` | 失败 | 0–7 | 8 | 难过、泄气，不要分离特效 | 未接。可映射心跳失败、网络错误 |
| 6 | `waiting` | 等待 | 0–5 | 6 | 期待用户点头或帮忙 | 未接。可映射验活等待 |
| 7 | `running` | 做事 | 0–5 | 6 | 思考/处理中，**不是**跑步 | 未接。可映射加载、保存 |
| 8 | `review` | 审阅 | 0–5 | 6 | 低头看完成物 | 未接。可映射看摘要 |

`running`（行 7）和 `running-right` / `running-left`（行 1–2）不是同一动作。行 7 是原地做事。

## 朝向（行 9–10）

屏幕坐标，顺时针，每 22.5° 一格。

| 行 | 列 | 角度 | 方向 |
|---:|---:|------|------|
| 9 | 0 | 000 | 上 |
| 9 | 1 | 022.5 | 右上 |
| 9 | 2 | 045 | 右上 |
| 9 | 3 | 067.5 | 右上 |
| 9 | 4 | 090 | 右 |
| 9 | 5 | 112.5 | 右下 |
| 9 | 6 | 135 | 右下 |
| 9 | 7 | 157.5 | 右下 |
| 10 | 0 | 180 | 下 |
| 10 | 1 | 202.5 | 左下 |
| 10 | 2 | 225 | 左下 |
| 10 | 3 | 247.5 | 左下 |
| 10 | 4 | 270 | 左 |
| 10 | 5 | 292.5 | 左上 |
| 10 | 6 | 315 | 左上 |
| 10 | 7 | 337.5 | 左上 |

上岸当前接的朝向：

- 贴左边 → `lookRight` = 行 9 列 4（090）
- 贴右边 → `lookLeft` = 行 10 列 4（270）
- 往下拖 → `lookDown` = 行 10 列 0（180）

## 接线规则

1. 新动作只往 `PetPose` 加一行，指定 `row`、`frames`（或 `column`）、`frameMs`、`loop`。
2. 占用列以本表「占用列」为准，不要按 8 帧一刀切；`idle` 若改为播全行，帧数是 7 不是 6。
3. 帧推进用 `Timer.periodic`，禁止 `AnimationController.repeat()`，以免 `pumpAndSettle` 挂起。
4. 「减弱动态效果」时停在该动作第 0 帧。
5. 这是本地精灵，不是 AI 入口；不要为动作加对话或模型调用。
6. 默认节奏（可再调，改前先看 `PetPose` 现况）：待机偏慢，位移步态中等，挥手一次性。

## 当前 App 状态机

```text
登录后 → idle
拖动按主轴：
  |dx| ≥ |dy| 且 dx<0 → runLeft
  |dx| ≥ |dy| 且 dx>0 → runRight
  |dy| > |dx| 且 dy<0 → jumping（往上）
  |dy| > |dx| 且 dy>0 → lookDown（往下）
松手 → idle
点击 → wave → idle
全屏 → 贴最近左右边 + lookRight/lookLeft
点探出 → 滑回安全区 + wave；仍全屏则数秒后再贴边
退出全屏 → 回到贴边前位置 + idle
```

未接的 `failed` / `waiting` / `running` / `review` / 其余朝向，没有产品确认前不要顺手接上。
