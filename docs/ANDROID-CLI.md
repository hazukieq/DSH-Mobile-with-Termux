# Android CLI 与官方 skills（本项目开发手册）

> **开发文档，不随包发布。** 这份是给「开发 DSH Mobile 的人/agent」看的，
> 不要拷进 `app/src/main/assets/docs/`（那里只放发给 App 内 agent 与用户的产物文档，
> 门禁脚本 `scripts/check-bundled-docs.py` 会拦）。

`android` CLI 是我们做 Android 侧工作的首选工具（见根 `AGENTS.md`「Android CLI 优先」）。
它把「装机 / 看屏幕 / 点控件 / 读布局 / 查文档 / 查 APK / 管 SDK / 管技能」都收在一个命令里，
比裸 `adb` 快（增量安装、annotated 截图）也更稳（不猜坐标、不猜组件名）。

## 1. 环境

- 可执行：`~/.local/bin/android`（`android --version`，2026-11 时 1.1.16717390+）。
- SDK：`android info` 应输出 `sdk: /home/hazukie/Android/Sdk`；也可 `--sdk=` 显式指定。
- 升级：`android update`（若卡住，直接下载链接见 `android info` 的提示）。

## 2. 常用命令（本项目实测可用）

| 场景 | 命令 | 说明 |
|---|---|---|
| 装机并启动 | `android run --device <serial> <apk>` | 默认 `--use-delta-install true`，比 `adb install` 快 |
| 只装机 | `android install --device <serial> <apk>` | 不激活组件 |
| 看屏幕 | `android screen capture --device <serial> -a -o /tmp/s.png` | `-a` 画编号控件框；读图时直接引用 #1/#2 |
| 按编号定位 | `android screen resolve ...` | 把 #编号替换成坐标，避免手算 |
| 点/按键/滑动 | `android input tap/key/swipe ...`（`android input --help`） | 配合 `-a` 的编号 |
| 布局树 | `android layout --device <serial> [--flat] [--full]` | 首次会装 instrumentation server，之后快 |
| 官方文档 | `android docs search "<query>"` / `android docs fetch kb://...` | 官方 Knowledge Base；`search` 必须带子命令，关键词加引号 |
| 查 APK | `android analyze <apk> [--analysis=basic,optimization]` | 体积/权限/组件/优化 |
| 描述工程 | `android describe` | 输出构建目标与产物路径 JSON |
| 模拟器 | `android emulator start/stop/list` | |
| SDK 管理 | `android sdk install/list/...` | |
| 技能 | `android skills add/find/list/remove/update` | 见下一节 |

不要用 `android build` 替本项目的构建：我们的包需要引擎层 zip、libvips 闭包、pnpm 载荷这些前置，
只有 `gradle assembleRelease -Pabi=arm64-v8a` 会处理。

## 3. 官方 skills

- 规范：open-standard agent skills（`https://agentskills.io/home`）—— 每个技能是一个目录，核心是 `SKILL.md`，
  frontmatter 至少含 `name` / `description`（官方还有 `license` / `metadata.keywords`），正文写触发条件、工作流、代码骨架、反例。
- 仓库：`https://github.com/android/skills`（Google 官方，2026-11 时 **36 个技能 / 15 个分类**）：
  `build-system`、`camera`、`cars`、`device-ai`、`devtools`、`identity`、`jetpack-compose`、`media`、
  `navigation`、`performance`、`play`、`profilers`、`security`、`system`、`testing`、`tv`、`wear`、`xr`。
- 官方说明：这些技能聚焦「LLM 容易做错的用例与工作流」，不重复模型已擅长的内容（如基础 Compose 写法）。

### 与 DSH 的对接（重要）

`android skills add` 只会装到各 agent 的目录（Claude Code 是 `~/.claude/skills`），
**DSH 读的是 `~/.dsh/skills`**。所以：

```bash
# 装到 claude 目录后，同步到 DSH
android skills add <skill-id>            # 或 --all
cp -a ~/.claude/skills/<skill-id>/. ~/.dsh/skills/<skill-id>/
```

我们已把 36 个官方技能全部装进 `~/.dsh/skills`（与自建技能合计 59 个）。

**软链方案（已启用，免手动同步）**：把 DSH 侧的技能目录指向 Claude 侧，`android skills update` 后自动生效。

```bash
trash ~/.dsh/skills/<skill-id>          # 删掉旧副本（先确认没本地改动）
ln -s ~/.claude/skills/<skill-id> ~/.dsh/skills/<skill-id>
```

已对 `android-cli` 做了这个切换（`android-cli -> /home/hazukie/.claude/skills/android-cli`，可正常读到 SKILL.md）。
DSH 侧扫描技能时按路径读 `SKILL.md`，`fs.readFile` 会跟随符号链接，所以内容与 Claude 侧实时一致。
若某天某技能在 DSH 里没被加载，退回 `cp -a` 拷贝即可（保留硬副本，代价是每次 update 要重拷）。

## 4. 本项目常用的官方技能

| 场景 | 技能 |
|---|---|
| 权限 / exported 组件 / IPC 审计 | `android-permissions-security`、`android-intent-security` |
| ANR 排查 | `anr-audit-and-fix` |
| 包体与 R8 | `r8-analyzer` |
| 性能剖析 | `android-profiler`、`jetpack-compose-performance` |
| 自适应/多形态 | `adaptive`、`edge-to-edge`、`leanback-to-compose-tv-migration` |
| 测试 | `testing-setup` |

## 5. 已验证的坑

- `android skills list --agent=dsh` 不支持（合法 agent 列表里没有 dsh）→ 只能先装到 claude 目录再 `cp -a`。
- `android layout` / `screen resolve` 首次会往设备装 instrumentation server，设备存储紧张或网络差时要留时间。
- `android screen capture` 拿到的是真实当前画面（锁屏/黑屏也会如实拍），不要拿它当「App 已在前台」的证据。
