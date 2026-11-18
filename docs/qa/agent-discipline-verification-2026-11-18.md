# 纪律技能注入核验（2026-11-18）

用户目标：将 App 内 AGENTS.md 中容易被忽略的执行规则整理为纪律技能，在 DeepSeek agent 续行过程中提醒。
参考附件 `a11y-tap-discipline.zip` 只作为待审阅资料，未执行其安装命令，也未照搬其历史案例与未经核实的断言。
仓库根 AGENTS.md 的开发/发版规则没有注入给 App 内 agent。

## 规则与加载

| 技能 | 约束 |
| --- | --- |
| task-execution-discipline | 保持当前目标和约束、响应插话后续行、区分原生/WebUI/终端、连续两次失败换路线、交接与完成停止条件 |
| a11y-tap-discipline | 当前前台/目标区域、可操作节点与容器、坐标与视觉兜底、点击语义、回执与目标状态 |
| evidence-discipline | 原始回执、解析空值排查、文档先行、用户纠正/停止、证据与推测边界 |

源文件在 `app/src/main/assets/skills/<name>/SKILL.md`。`AgentContextSeed` 启动时从 APK
读取正文预加载进 App 管理的指令段，不依赖模型自行选择 skill 工具。种子版本为 120，
即使版本号未变化，启动时也会对比管理段实际内容并更新；用户指令段沿用原有保留流程。
技能发现副本位于 `$DSH_HOME/skills`：按上次部署 SHA-256 及已知原版内容识别所有权，
更新未改动的内置副本，保留用户修改、未知同名文件和符号链接。技能全文的预加载来源始终是 APK。

`android-discipline` 插件通过实际引擎提供的 `agent/pre-step` waterfall 工作：

- 每个 agent 新一轮的已准入步骤收到短提醒；新用户插话/纠正进入步骤时立即提醒。
- 持续执行时，距离上次提醒至少 6 个模型步骤再补充一次；不是每次触摸都插入全文。
- 使用真实 `createUserMessage` 工厂，来源标为 `android-discipline`、`form: instructions`，
  保留用户消息对象、顺序和其他准入字段；不伪装成用户新任务。
- 拒绝、取消、空的首步骤和同一步重试不重复追加；不调用 inject / steer / followup，不唤醒空闲 agent。
- 短提醒为 1140 字节，构建门禁限制在 4096 字节内；完整技能只通过种子加载。
- 若引擎 API 不兼容，日志会报告 periodic reminders unavailable；预加载的完整技能仍保留，
  不因提醒插件初始化失败而中断引擎。

## 附件中修正的冲突

- 将“坐标只能来自树”改为优先新鲜场景几何；缺节点/几何过期时允许已确认像素映射的原图，禁止按缩放预览猜坐标。
- 节点少不能直接判定 Canvas；WebView 也可能有完整 a11y 树。
- ownOverlay 只作诊断，结合交互控件、手势避让和目标状态判断，不直接宣布点击被吞。
- changed 只证明画面变化，不证明选中了正确目标；未变化也不否定原本已满足的目标状态。
- 成功 handoff / 明确终止才停止该动作链；verify:none 单独出现不代表任务完成。
- 回执 hint 不扩展授权，不据此自动切 Root/Shizuku。

对应 ACCESSIBILITY、CLICK_CHAIN_UPGRADE、DSH-MOBILE 的相关说明已同步，避免技能与参考文档互相矛盾。

## 验证

```sh
python3 scripts/check-agent-skills.py
node --test scripts/tests/test-discipline.mjs
bash scripts/acceptance.sh
python3 scripts/test-agent-discipline-device.py --device <serial>
```

- 三个 SKILL.md 均通过 skill-creator 的结构校验。
- 7 项 Node 回归：首次/周期/新轮次/独立 agent/用户纠正、拒绝/取消/空首步骤、重试去重、保留真实用户输入、不新增排队任务。
- 37 项 JVM 单测通过，含新增 7 项技能部署测试：内置副本升级、用户修改/未知同名文件/符号链接保护、引用更新、frontmatter 和路径边界。
- 完整 acceptance 通过 18 项：包括静态门禁、升级回归、纪律提醒回归、JVM、Release 构建、APK 内容断言。
- Redmi M2012K11AC，Normal 模式，应用 UID 10278：隔离目录中加载实际插件，使用手机
  现有引擎的真实消息工厂，验证首次/周期/用户纠正提醒和拒绝/空首步骤/重试边界；临时文件已清理。

手机测试不调用 Root，不安装新 APK、不改手机当前 AGENTS/技能/引擎配置，也不发起模型请求。
它验证注入机制与引擎工厂兼容性，不构成模型长期执行遵循率的评测。当前手机要使用内置机制，
仍需安装此次构建的 APK 并启动引擎；已有会话后续是否采纳提醒，受模型行为影响。
