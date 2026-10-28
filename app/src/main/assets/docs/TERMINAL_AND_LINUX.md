# 终端

> DSH Mobile 内置一个真 PTY 终端：多会话、可回滚、CJK 宽字符、真彩，环境与 AI 的 shell
> 工具完全一致。运行时由「Termux bootstrap + 适配版 dsh 引擎」两层组成，`pkg` / `apt` /
> `dpkg` 都是 Termux 官方实现。

## 一、终端

### 入口

- **桌面图标**：`DSH Terminal`（独立于主 App 的第二个 LAUNCHER 入口）
- **设置 → 终端**

### 架构

```
软键盘 / 额外按键条 / 粘贴
        │  sendText / sendKey / sendControlChar
        ▼
   TerminalView  ──Canvas 自绘（等宽字体、256/真彩、光标闪烁）──▶ 屏幕
        │ attach(session)
        ▼
   TerminalSession ── reader 线程 ──▶ TerminalEmulator（VT100/xterm 状态机 + 屏缓冲）
        │ write()/resize()                  │ onChanged 节流
        ▼                                    ▼
   dsh_pty.c（JNI） ── fork/execve + PTY ──▶ 子进程（zsh / bash）
```

- **PTY 层**：`dsh_pty.c` 维护一张 16 槽的会话表。引擎原有的 `Pty.nativeForkPty`
  等符号保持不变（委托到 0 号槽位），引擎侧行为不受影响。
- **VT 模拟器**：GROUND / ESC / CSI / OSC 状态机，增量 UTF-8 解码（跨 chunk 生效），
  替代屏（DECSET 1049，vim/top 必需）、滚动区（DECSTBM）、插入/删除行列、
  自动换行与延迟换行、光标保存恢复、OSC 0/2 标题、BEL。
  颜色支持 16 / 256（`38;5;n`）/ 真彩（`38;2;r;g;b`）。
- **CJK 宽字符**：按 wcwidth 规则占 2 格，第二格标记为延续；末列宽字符换行而非溢出。
- **回滚**：主屏 5000 行；回看时新输出**不会**把视图拽回底部（只有本来就在底部才跟随）。
- **会话**：多会话并存，顶部标签条切换，长按标签关闭；旋转/改字号不重建进程。
  App 内最多 7 个终端，引擎侧（WebUI 侧边栏）另限 6 个。

### 交互

| 操作 | 位置 |
|---|---|
| Esc / Tab / ^C / ^D / ^L / ^Z / Home / End / PgUp / PgDn / 方向键 | 屏幕底部额外按键条（可横向滑动） |
| 字号 ± | 菜单 ⋮ → Font size ±（8–26sp，持久化） |
| 复制全部输出 | 菜单 ⋮ → Copy all output（回滚 + 当前屏，末尾连续空行折叠） |
| 粘贴 | 菜单 ⋮ → Paste（走系统剪贴板，不经过 IME） |
| 滚动回看 | 手指**下滑** = 看更早的输出，**上滑** = 回到更新输出；回看时右上角显示 `↓ 行数` |
| 回到底部 | 菜单 ⋮ → Scroll to bottom |
| 新会话 | 顶栏 ＋ |

- **返回键 / 顶栏 ←**：正在回看历史时先回到底部，再按一次才退出终端。
- **软键盘**：键盘打开时，系统仍会先用返回键收起键盘（与系统终端一致），第二次才由 App 处理。
- **输入**：IME 走 `commitText`；硬件键盘与 `adb shell input text` 走 `KeyEvent`，
  可打印字符取自 `event.unicodeChar` 与 `ACTION_MULTIPLE` 的 `characters`，直接写 PTY。

### 环境：与 AI 完全一致

会话默认使用 `pkg install zsh` 装好的 `$PREFIX/bin/zsh`；没有 zsh 时用引擎自带的
`$PREFIX/bin/bash -i`。环境变量直接来自 `EngineConfig.buildEnv()` —— 也就是说
PATH / LD_LIBRARY_PATH / PREFIX / `GIT_EXEC_PATH` / `PYTHONHOME` 等**与 AI 的 shell
工具同源**。这样不会出现「AI 能跑的 git，终端里报错」的分裂；反过来，用户在终端里
复现 AI 的命令就是逐字级复现。

引擎运行时尚未安装时自动回落到 `/system/bin/sh`（toybox），至少可用，并在标题里标注
`Android (toybox)`。

## 二、运行时：两层结构

| 层 | 内容 | 来源 |
|---|---|---|
| Termux 基础环境 | bash / apt / dpkg / pkg / coreutils / sed / tar … | `assets/bootstrap-aarch64.zip` 解到 `<files>/usr`，`SYMLINKS.txt` 重建符号链接 |
| dsh 引擎 | node + `lib/node_modules/@deepseek-ai/dsh` 适配版闭包 + ripgrep + bash | `assets/engine-dsh-aarch64.zip`（随 APK 打包） |

- `$PREFIX` 就是 `/data/data/com.termux/files/usr`（applicationId = `com.termux`）。
- 引擎层的 `bin/node`、`bin/rg`、`bin/bash` 是 App 管理的 wrapper，真实二进制与依赖库在
  `$PREFIX/lib/dsh-engine/`；被外部覆盖时自愈逻辑会恢复 wrapper。
- **不能用 npm 安装 `@deepseek-ai/dsh`**：上游包在 Android/Termux 上装不起来，本项目
  存在的意义就是这份适配运行时；npm 还会把更新的嵌套依赖铺进来，覆盖适配层。
- **不要用 `pkg` 装/升级 `nodejs`、`ripgrep`**：会换掉上面那些 wrapper。

### 启动顺序

- **同步**：解引擎层（一次性，约 30 s），随后立刻起引擎，UI 马上可用。
- **后台**：解 Termux bootstrap（apt/dpkg/pkg/coreutils），解完把 apt 源写成国内镜像。

首次启动后、bootstrap 解压完成前，`pkg` / `apt` 尚不可用（可用 `$PREFIX/bin/apt`
是否存在判断）；引擎本身不依赖它。

### apt 源

`BootstrapInstaller` 在 `$PREFIX/etc/apt/sources.list` 写入清华 TUNA 的 Termux 主仓库
（仅在缺失或仍是旧默认源时重写），之后用户可以自行修改。

## 三、包管理：apt / dpkg / pkg

Termux 基础环境是官方 bootstrap，包管理器可以真装、真卸载、真解析依赖，无需 root：

```
pkg update
pkg install -y python git clang make openssh vim ffmpeg
pkg search <关键词>
pkg list-installed
apt install -y <包名>      # apt / dpkg 也可直接用
```

- 普通包随意装，`pkg upgrade` 对普通包也可用；别删 `bash`、`termux-tools`、
  `libandroid-support`（shell 与 Termux 脚本依赖它们）。
- **root 模式下 `pkg`/`apt` 拒绝运行**，且 root 拥有的文件会破坏 App 后续使用：
  需要装包时先用 Normal/Shizuku 模式装完再切回。
- **不能与官方 Termux 共存**：同包名不同签名。安装本 APK 前需卸载 Termux；
  从旧包名（`app.dsh.mobile`）升级时，`LegacyDataMigrator` 会把 dsh-home 文本数据里的
  旧路径改写成本包名，并按 `workspace.json` 重建工作区目录（幂等）。

## 四、引擎热更新：`dsh-update`

运行时是两层，**热更新只换引擎层**：

```
<files>/usr/lib/node_modules           ← 换
<files>/usr/bin/node, bin/rg            ← 换
<files>/usr/.dsh-engine-version         ← 换
（apt/dpkg/bash/coreutils 等 bootstrap 一个都不碰）
```

### 命令

```
dsh-update status                        # 本地 dsh/node/引擎版本、暂存与回滚状态
dsh-update check [--manifest URL]        # 本地版本 vs GitHub 上的上游与适配包（不查 npm）
dsh-update pull [版本] [--apply]         # 从 feed 下载引擎 tar.gz，校验 sha256 后暂存
dsh-update stage <url|path> [sha256]     # 暂存指定包（离线/内测）
dsh-update apply                         # 备份 → 换入 → 重启 → 健康检查（失败自动回滚）
dsh-update rollback                      # 回到上一份引擎
dsh-update restart                       # 只重启引擎
dsh-update howto                         # 打印「自己编译上游 dsh」的完整步骤
```

### 自己编译上游 dsh

引擎层 = Termux 版 node + `@deepseek-ai/dsh` 依赖闭包 + 一组 Android 兼容补丁，所以
「编译」是在 **x86_64 Linux 主机**上重新组装，不是手机上 gcc（手机上也没有工具链）。

```sh
# 0) 选上游 tag（不是 npm dist-tag），例如 dsh-v0.2.2-alpha.2
# 1) 主机依赖：node/npm(>=20)、pnpm、unzip、zip、python3、llvm-readelf（NDK 自带）
export NPM_REGISTRY=https://registry.npmmirror.com
export TERMUX_REPO=https://mirror.nju.edu.cn/termux/apt/termux-main
# 2) 取上游 dsh + 打 bionic 兼容补丁 + 收 Termux 运行时
DSH_VERSION=0.2.2-alpha.2 scripts/collect-termux-runtime.sh /tmp/rt-arm64.zip aarch64
# 3) 组装引擎层（同热更新包布局，顺手校验 ABI）
scripts/build-engine-layer.sh --runtime /tmp/rt-arm64.zip --arch aarch64 \
    --out /tmp/engine-mine --engine-version "0.2.2-alpha.2+my1"
# 4) 推进手机并切换（sha256 用 sha256sum 算）
adb push /tmp/engine-mine/engine-dsh-aarch64.tar.gz /sdcard/Download/
dsh-update stage /sdcard/Download/engine-dsh-aarch64.tar.gz <sha256>
dsh-update apply            # 换层 → 等旧引擎退出 → 健康检查；失败自动回滚
dsh-update rollback         # 不满意随时回上一份
```

- **不要跳过第 2 步**：apiproxy / webview-polyfill / koffi 打桩 / session-persistence /
  flock 等 bionic 兼容补丁都在那里；跳过通常表现为 WebUI 白屏或引擎起不来。
- **镜像**：Termux 源 NJU 最快（实测 ~521 kB/s），USTC 可用，TUNA/BFSU 的 termux 路径会
  403；npm 用 npmmirror（~1.5 MB/s）。
- **引擎 tar 的最小布局**（自己手搓包时必须满足）：`lib/node_modules/**`（dshEntry 按 npm
  布局解析，缺了就是空壳）+ `.dsh-engine-version`（status/回滚识别）；`bin/node`、`bin/rg`
  可选，缺了沿用旧引擎/运行时那份。**不要把 runtime.zip 直接 stage**。
- **手机端自建也可行**（≈10–15 分钟，主要在 `pnpm install` 与构建；完整步骤、内存/存储/换源
  三个硬约束见 [ENGINE-BUILD.md](ENGINE-BUILD.md) §1.7）：
  `pkg install nodejs-lts pnpm zip` → `git clone --depth 1 -b dsh-v0.2.2-alpha.2
  https://github.com/deepseek-ai/deepseek-harness.git` → `pnpm install --frozen-lockfile` →
  `pnpm build:official` → 把 `lib/node_modules` 闭包 + `.dsh-engine-version` 打 tar 后
  `stage`/`apply`。内存吃紧时给 node 加 `--max-old-space-size=4096`。
- 想验证自己的包：`dsh-update stage` 后先 `apply`，`status` 里看引擎版本与健康检查结果。


- 版本按 `@deepseek-ai/dsh` 判断，不按 APK 版本。
- `apply` 把旧层移到 `$PREFIX/.engine-prev`，换入后重启引擎，用
  `http://127.0.0.1:3080/` 做健康检查（120 s）；新树不健康自动回滚。
- 只换引擎层，所以更新引擎不会破坏 `pkg`/`apt`。

### feed 与 CI

- 默认 feed：本仓库 release `dsh-engine-latest/engine-manifest.json`
  （`{dsh_version, engine_version, assets.<arch>.{url,sha256,size}}`）。
- `check` 只走 GitHub，分两路：上游 `deepseek-ai/deepseek-harness` 的 `dsh-v*` release，
  与本仓库是否已发布带 `engine-manifest.json` 的适配引擎包；没有就走 `feed: unavailable`
  并如实说明「上游有新版但适配包未发布」，不拿 npm 冒充。
- `.github/workflows/dsh-runtime.yml` 产出 `engine-dsh-*.tar.gz` 与 manifest，发布到
  滚动 release `dsh-engine-latest`，并归档到 `dsh-runtime-<dsh 版本>`。

## 五、WebUI 侧边栏终端（node-pty 桥）

dsh WebUI（App 内 WebView，`127.0.0.1:3080`）侧边栏的终端需要两样东西：
node-pty 的 Android 实现，以及认 android 的进程检查器。做法是把真 PTY 留在 App 进程：

```
引擎(node)                     App 进程
node-pty shim  ──HTTP──▶ AgentBridge /pty/{open,write,resize,kill,close,read}
                                └─▶ libdshpty.so（fork/exec + /dev/ptmx）
```

- **shim**：`assets/runtime-overlay/lib/node_modules/node-pty/`，由
  `EngineConfig.applyRuntimeOverlays()` 每次引擎启动时覆盖进引擎树（幂等；只在目标不是
  原生 node-pty 时覆盖）。`spawn()` 保持**同步**语义：用子 node 进程做一次 HTTP open，
  父进程 `execFileSync` 等它（引擎在 spawn 返回后立刻读取 `terminal.pid`）。
- **桥**：每会话一个 reader 线程 + 每会话 512 KB 环形缓冲（绝对序号 `seq`），
  `GET /pty/read?id&seq&timeout` 长轮询取增量（base64）；进程结束后返回 `exit_code/signal`。
- **进程检查器**：`EngineConfig.patchEngineForAndroid()` 在引擎启动时按**内容锚点**扫描
  `@deepseek-ai/dsh-subprocess-local/lib/*.js`（文件名带内容哈希，不能写死），把
  `if (platform === "linux")` 改成 `linux || android`。Android 有 `/proc`，检查器可用；
  幂等，运行时升级后自动按新文件重打。
- **默认 shell**：`buildEnv()` 注入 `SHELL=$PREFIX/bin/bash`，侧边栏拿到的是引擎自带
  bash（App 的 PS1、rc、补全）。

### 实现约束（C 层）

| 约束 | 原因 |
|---|---|
| 所有 fork 放在常驻 `pty-spawner` 线程上执行 | `PR_SET_PDEATHSIG` 绑的是**线程**，不是进程 |
| 子进程在 exec 前不 `malloc` / `opendir` | 多线程 ART 下会崩在 libsigchain；`close_inherited_fds` 用 `close(3..1023)` 循环 |
| 子进程只复位「普通信号」白名单 | 崩溃类信号交给 execve 自动复位 |
| `cwd` 不可用时回落到 dsh-home 并记日志 | 否则子进程 `_exit(125)`，终端一开就退 |

## 六、边界与已知限制

- 回滚不重排：`resize` 保留内容、裁剪/补空行，不做 reflow（与多数移动端终端一致）。
- 终端只能跑 bionic 二进制；glibc 程序与 `.deb` 里的 glibc 工具不可直接用。
- `pkg` / `apt` 依赖 bootstrap 解压完成；首启后台进行，期间 shell 可用、包管理暂不可用。
- 引擎热更新只换引擎层，不改 APK、`dsh-home`、`workspaces` 和已有会话；回滚点只保留
  一次 `apply` 的机会，之后 `.engine-prev` 被清掉。
- WebUI 侧边栏终端依赖引擎健康（`:3080`）与 overlay 覆盖成功。
- 与官方 Termux 同包名、不同签名，不能共存。

## 七、排错

| 现象 | 处理 |
|---|---|
| 终端里 `bash: not found` | 引擎运行时没装好；标题会显示 `Android (toybox)` 回落 |
| 终端显示乱码 | 确认 `TERM=xterm-256color`（App 内固定注入） |
| `pkg` / `apt`: not found | bootstrap 尚未解压完成，稍后重试；检查 `$PREFIX/bin/apt` |
| WebUI 侧边栏报 `terminal inspection is unsupported on platform android` | 引擎补丁未生效：重启引擎，确认运行时版本 |
| WebUI 侧边栏报 `node-pty unavailable in this Android build` | overlay 未覆盖：重启引擎让 `applyRuntimeOverlays()` 重跑 |
| `dsh-update check` 显示 `feed: unavailable` | 本仓库还没有该 dsh 版本的适配引擎包；可等 CI 或用含新引擎的 APK 升级 |

### dsh-update 现在同时换「JS 闭包」与「原生库」（v2.5.62 起）

引擎层其实有两块可换：

| 层 | 路径 | 什么时候变 |
|---|---|---|
| JS 闭包 | `$PREFIX/lib/node_modules` | 每次升级 dsh 都要换 |
| 原生库 | `$PREFIX/lib/dsh-engine/lib` | 换 node、加系统库（如 libvips、ICU）时要换 |

`dsh-update apply` 会同时替换这两块：旧的 `lib/dsh-engine/lib` 整个 `mv` 到 `.engine-prev/engine-lib`，
新的从 staged 目录 `mv` 进来，回执里会打出 `apply: native libs: N files`。
`dsh-update rollback` 两块一起退回。

这样做的好处：`sharp` 这类原生模块依赖的 `libvips` 闭包（69 个 `.so`，约 39 MB）可以随引擎层下发，
设备上不必 `pkg install libvips`，也不必重装 APK —— 升级完就是原生。

注意两点：

1. **空间**：staged 目录要额外放一份完整闭包（约 1.2 GB）+ 原生库；设备剩余空间 < 3 GB 时
   `stage` 可能失败（`ENOSPC`）。脚本会先打 `apply: native libs` 再重启，失败会自动回滚。
2. **版本号相同不产生回滚点**：`apply` 只在新旧 `engine` 版本不同时才留 `rollback`，
   同版本重复 apply 视为原地重刷。

最小 tar 布局（`dsh-update stage` 认这个）：

```
.dsh-engine-version            # 0.2.2-alpha.2+xxx
lib/node_modules/**            # JS 闭包
lib/dsh-engine/lib/*.so*       # 原生库（可选，有就一起换）
```
