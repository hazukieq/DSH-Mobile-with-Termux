
## Android CLI 优先（本项目开发约定）

**所有 Android 侧的构建/装机/看屏幕/看布局/查文档，优先用 `android` CLI，`adb` 只作兜底。**

`android` 就在 PATH（`~/.local/bin/android`，1.1.16717390+），`android info` 认到 `~/Android/Sdk`。

| 场景 | 首选命令 | 备注 |
|---|---|---|
| 装机并启动 | `android run --device <serial> <apk>` | 比 adb 快（delta install 默认开）；细节见 `android run --help` |
| 只装机不启动 | `android install --device <serial> <apk>` | 等价 `adb install -r` 但走增量 |
| 看屏幕 | `android screen capture --device <serial> -a -o /tmp/x.png` | `-a` 会画带编号的控件框，读图时可直接引用 #1/#2 而不是算像素 |
| 按控件点击/输入 | `android input tap --device <serial> ...`（见 `android input --help`） | 配合 `screen capture -a` 的编号用，免猜坐标 |
| 看 UI 布局树 | `android layout --device <serial> [--flat] [--full]` | 首次会装 instrumentation server，之后快；卡住就退回 `adb shell uiautomator dump` |
| 查官方文档 | `android docs <query>` | 官方 Knowledge Base，比网页搜索准 |
| 查 APK 详情 | `android analyze <apk>` | 体积/权限/组件/优化项 |
| 编译 | `android build` | 本项目仍走 `gradle assembleRelease -Pabi=arm64-v8a`（有 pnpm/vips 前置），别用 android build 替掉 |
| 技能 | `android skills list` / `android skills add ...` | ⚠️ 只写 `~/.claude/skills`，DSH 读 `~/.dsh/skills`，装完要手动同步（`cp -a ~/.claude/skills/<name>/. ~/.dsh/skills/<name>/`） |

约定：
- 截图/交互类工作先 `android screen capture -a` 拿带编号的图，再用编号做点击，避免手算坐标。
- `android layout`、`android screen resolve` 首次使用会往设备装 instrumentation，遇到设备存储紧张/网络差要留时间。
- 本项目专属前置（引擎层 zip、vips 闭包、pnpm 载荷）只有我们的 gradle 流程会处理，`android build` 不会。
- 完整手册（命令表 / 官方 skills 规范与仓库分类 / DSH 对接 / 已验证的坑）见 `docs/ANDROID-CLI.md`。

### 文档两类，别混

- **开发文档**（`docs/`，给改代码的人/agent 看）：`ANDROID-CLI.md`、`refactor_guide.md`、`code_review.md`、`AI_CONTEXT.md`。**绝不拷进 `app/src/main/assets/docs/`**，那是随 APK 发布给用户与 App 内 agent 的。
- **产物文档**（`app/src/main/assets/docs/`，随包）：`README.md`、`ENGINE-BUILD.md`、`SECURITY-AUDIT.md`、`A11Y-SCOPE.md` 等面向使用者的说明。
- 门禁：`python3 scripts/check-bundled-docs.py`（漏打包会 exit 1）。
- 门禁：`python3 scripts/check-overlay-avoid.py`（禁止「路径表 → 隐藏悬浮窗」的写法复发，
  历史两次事故见 `docs/OVERLAY.md` §3.p；`bash scripts/acceptance.sh --static` 已包含它）。

## 分支模型（dev → main）

- **`main` = 稳定线**：只接受来自 `dev` 的合并，日常不直接提交；tag / Release 都打在 `main` 上。
- **`dev` = 工作线 + 恢复快照**：所有改动先提交到 `dev`；它同时也是 main 的一份快照，
  万一需要回滚而 git 历史不便操作（例如要丢弃一批已推送的提交），可以直接
  `git reset --hard dev@{当时的位置}` 或从 `dev` 的某个提交重建分支。
- 流程：`dev` 上改 → 真机验证 + `bash scripts/acceptance.sh --static` 通过 → `git checkout main && git merge --ff-only dev`（能快进就快进，避免无谓 merge commit）→ push → 需要发版时打 tag。
- 发版后若 `main` 有热修（极少见），同样先回到 `dev` 做，再合并；不要两边同时改。
- 合并前确认 `dev` 与 `main` 的差异只有预期提交：`git log --oneline main..dev`。
