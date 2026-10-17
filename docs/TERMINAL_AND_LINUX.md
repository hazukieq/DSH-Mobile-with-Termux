# 终端 + Linux 环境

> 2026-10-18 新增。给 DSH Mobile 补上两件它原本没有的东西：一个**真终端**，
> 和一个**可选的 Linux 用户空间**（proot 发行版）。目标是让「dsh 在手机上能干的活」
> 不再被 bionic 快照的边界卡住，同时让人能看见、能接管 AI 正在跑的东西。

## 为什么加这个

原设计把 Node.js 运行时与工具链以 bionic 二进制直接打进 APK，装完即用，代价是：

1. **没有终端 UI** —— 唯一的 shell 是 AI 的 `bash -c` 工具，不可见、不可交互；
2. **Termux 扩展的硬编码前缀** —— `ExtensionManager.rewriteTermuxPaths` 明确跳过 ELF，
   sshd / git-core 这类路径改不了（代码注释原话：「长期由 PRoot 活环境系统性解决」）；
3. **glibc / apt 世界够不着** —— 只有 bionic 闭包 + 19 项扩展。

所以这一版**不推翻原路线**，而是在旁边加一条支线：需要 glibc / apt 时进 Linux，
日常仍然零下载用 bionic 工具链。两条路线共享同一个 `$HOME`。

## 一、终端（Terminal）

### 入口

- **桌面图标**：`DSH Terminal`（第二个 LAUNCHER 入口，独立于主 App 图标）
- **设置 → 扩展中心分组 → 终端**
- **Linux 环境页 → 打开终端**（直接进某个发行版）

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
   dsh_pty.c（JNI） ── fork/execve + PTY ──▶ 子进程（bash / proot）
```

- **PTY 层**：`dsh_pty.c` 从「单引擎子进程」重构为**会话表**（多槽位），
  引擎原有的 `Pty.nativeForkPty` 等符号保持不变（委托到 0 号槽位），
  所以引擎侧行为一字未改。
- **VT 模拟器**：GROUND / ESC / CSI / OSC 状态机，增量 UTF-8 解码（跨 chunk 生效），
  替代屏（DECSET 1049，vim/top 必需）、滚动区（DECSTBM）、插入/删除行列、
  自动换行与延迟换行、光标保存恢复、OSC 0/2 标题、BEL。
  颜色支持 16 / 256（`38;5;n`）/ 真彩（`38;2;r;g;b`）。
- **CJK 宽字符**：按 wcwidth 规则占 2 格，第二格标记为延续；末列宽字符换行而非溢出。
- **回滚**：主屏 5000 行；回看时新输出**不会**把视图拽回底部（只有本来就在底部才跟随）。
- **会话**：多会话并存，顶部标签条切换，长按标签关闭；旋转/改字号不重建进程。

### 交互

| 操作 | 位置 |
|---|---|
| Esc / Tab / ^C / ^D / ^L / ^Z / Home / End / PgUp / PgDn / 方向键 | 屏幕底部额外按键条（可横向滑动） |
| 字号 ± | 菜单 ⋮ → Font size ±（8–26sp，持久化） |
| 复制全部输出 | 菜单 ⋮ → Copy all output（回滚 + 当前屏，末尾连续空行折叠） |
| 粘贴 | 菜单 ⋮ → Paste（走系统剪贴板，不经过 IME） |
| 滚动回看 | 手指**下滑** = 看更早的输出，**上滑** = 回到更新输出（与 Android 其它滚动容器一致）；回看时右上角显示 `↓ 行数` |
| 回到底部 | 菜单 ⋮ → Scroll to bottom；**返回键/顶栏 ←** 在回看历史时先回到底部，再按一次退出终端 |
| 新会话 | 顶栏 ＋；选 Android / 已安装发行版 |

> 2026-10-18 修了两个交互 bug：① 返回键用了 `viewportTopRow < scrollbackRows - 1` 判定，
> 该条件恒为真 → 永远只执行 scrollToBottom、`finish()` 走不到，表现为「返回键没反应」；
> 改用 emulator 的 `snapshot().scrolledBack`（`viewTop < maxTop`）。② 触摸滚动方向曾与
> Android 相反（上滑看历史、下滑回最新），已翻转为标准方向。软键盘打开时，系统仍会先用
> 返回键收起键盘（与系统终端行为一致）——第二次才由 App 处理。

### 环境：与 AI 完全一致

Android 侧会话的命令是 `engine/bin/bash -i`，环境变量直接来自
`EngineConfig.buildEnv()` —— 也就是说 PATH / LD_LIBRARY_PATH / PREFIX / 扩展注入的
`GIT_EXEC_PATH` / `PYTHONHOME` 等**与 AI 的 shell 工具同源**。
这样不会出现「AI 能跑的 git，终端里报错」的分裂；反过来，用户在终端里复现 AI 的命令
就是逐字级复现。

引擎运行时尚未安装时自动回落到 `/system/bin/sh`（toybox），至少可用，并在标题里标注。

## 二、Linux 环境（proot）

### 支持的发行版

| id | 发行版 | 包管理 | 默认 shell | 下载体积 |
|---|---|---|---|---|
| `alpine` | Alpine Linux 3.20.3（musl） | `apk` | `/bin/sh` | ≈ 3.5 MB |
| `ubuntu` | Ubuntu Base 24.04.3（glibc） | `apt` | `/bin/bash` | ≈ 30 MB |

架构按 App 实际 ABI 选择：`arm64-v8a → aarch64/arm64`，`x86_64 → x86_64/amd64`。

### 安装做了什么

1. **proot 载荷**：从 Termux 官方仓库取 3 个 `.deb`
   （`proot 5.1.107.96` + `libtalloc 2.5.0` + `libandroid-shmem 0.7`），
   校验 SHA-256，解 `data.tar.xz`，把 `data/data/com.termux/files/usr/**`
   拍平到 `<filesDir>/linux/proot/`。**不执行** postinst（与扩展中心同一策略）。
2. **rootfs**：从镜像下载 `.tar.gz`，解到临时目录，再**原子 rename** 成
   `distros/<id>/rootfs`（中途失败不会留下半个 rootfs）。
3. **后配置**（纯文件写入，不 chroot）：
   - `/etc/resolv.conf`：`223.5.5.5` / `8.8.8.8` / `1.1.1.1`
   - `/etc/hosts`：localhost 双栈
   - Alpine：`/etc/apk/repositories` 指向阿里云镜像（官方 CDN 保留为注释）
   - Ubuntu：`/etc/apt/sources.list` 指向阿里云 `noble` 四组件 + updates/security
   - `/etc/profile.d/00-dsh.sh`：PS1、`LANG=C.UTF-8`
   - `/tmp` 权限 1777、`/root` 存在

### 启动命令（模拟器实测通过）

```sh
PROOT_LOADER=<home>/proot/libexec/proot/loader \
PROOT_TMP_DIR=<home>/tmp \
LD_LIBRARY_PATH=<home>/proot/lib \
PROOT_NO_SECCOMP=1 \
<home>/proot/bin/proot \
  --link2symlink --kill-on-exit -0 \
  -r <rootfs> -b /dev -b /proc -b /sys -b <appFilesDir> [-b /storage/emulated/0:/sdcard] \
  -w /root \
  /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin USER=root \
      /bin/sh -l
```

关键点：Termux 的 proot 把 loader 路径**编译期写死**成
`/data/data/com.termux/files/usr/libexec/proot/loader`，本机不存在；
proot 5.1.107 支持 `PROOT_LOADER` 覆盖（`src/execve/enter.c:571`），所以用环境变量指到
App 私有目录，**不需要改 ELF 二进制**。

### 目录布局

```
filesDir/linux/
  proot/                 proot + libtalloc + libandroid-shmem + libexec/proot/loader
  tmp/                   PROOT_TMP_DIR
  distros/<id>/rootfs/   发行版根文件系统
  distros/<id>/.installed
```

### 镜像与许可

- 国内优先：阿里云（rootfs）与 Termux 官方（deb）；失败按列表回退到官方 CDN。
  实测本机访问 TUNA / USTC 返回 403，故默认不使用。
- **rootfs 与 proot 都不随 APK 分发**：APK 里没有任何第三方 GPL 二进制。
  proot 为 GPL-2.0，Termux 包各自有许可证，安装时由用户设备直接从上游取得。

## 三、与 dsh 的打通

引擎启动时（`EngineConfig.buildEnv`）会注入：

| 变量 | 值 |
|---|---|
| `DSH_LINUX_HOME` | `<filesDir>/linux` |
| `PROOT_LOADER` | `<home>/proot/libexec/proot/loader` |
| `PROOT_TMP_DIR` | `<home>/tmp` |
| `PROOT_NO_SECCOMP` | `1` |
| `DSH_LINUX_DEFAULT` | 第一个已安装发行版 id |

并把 `<home>/proot/bin` 并入 PATH、`<home>/proot/lib` 并入 LD_LIBRARY_PATH。
门脚本 `linux` 由 `applyAgentGates` 从 `assets/gates/linux` 部署到 `engine/bin/`：

```
linux list                  # 已安装发行版（每行一个）
linux status [id]           # rootfs 路径 / proot 是否就绪
linux run <id> <cmd...>     # 在发行版内执行命令（流式 stdout/stderr，返回 guest 退出码）
linux shell <id> [cmd]      # 登录 shell（交互场景）
linux exec <cmd...>         # 在默认发行版内执行
linux path <id>            # 打印 rootfs 路径
```

例：

```sh
linux exec "uname -a && cat /etc/os-release"
linux run ubuntu "apt-get update && apt-get install -y jq"
```

Agent 的系统提示词（`AgentContextSeed.kt`）里新增了「Linux environment」一节，
说明何时该用它、哪些操作属于用户（安装发行版是用户点按钮），以及默认优先用 bionic
工具链。**安装发行版不是 AI 的职责**，避免 AI 自己去拉 rootfs。

## 六、模拟器实测证据（2026-10-18）

环境：Android 14（API 34）x86_64 AVD `dsh36`，KVM 加速；APK 为 debug + 内置 x86_64 runtime.zip（160 MB）。

| 项目 | 结果 |
|---|---|
| 构建 | `assembleDebug -Pabi=x86_64` 与 `-Pabi=arm64-v8a` 均 BUILD SUCCESSFUL；`check-i18n.py` 全绿 |
| APK 体积 | 185 MB（含 160 MB runtime.zip；不含 runtime 时 24 MB） |
| 引擎 | 首次启动 runtime 解压 → `engine healthy on :3080`（约 21 s，含 374 MB 解包） |
| 终端（Android 侧） | 标题 `Android`，提示符 `u0_a192@dsh:~$`；`node -v` → `v24.18.0`（与 AI 同一运行时） |
| 渲染 | 16 色 / 256 色 / 真彩、bold/underline/inverse、CJK 宽字符（`中文宽字符测试` 占位正确）、`✓` 无乱码 |
| 备用屏 | `CSIU ?1049 h` → 只显示 ALT 内容；退出后主屏内容保留 |
| 输入 | IME 输入可用；`adb shell input text`（硬件键路径，经 unicodeChar）可用；额外按键条可横向滚动到 KBD |
| KBD 键 | 点击前后 `dumpsys input_method` 的 `mInputShown`：true → false（收起键盘） |
| 多会话 | 标签条 `Android \| Alpine Linux \| Ubuntu Base` 可切换、可新建 |
| 发行版安装 | Alpine（3.5 MB）与 Ubuntu（30 MB）均在 10 s 内完成（阿里云镜像），proot 载荷同时装好 |
| `linux` 门脚本 | `linux list` → `alpine`/`ubuntu`；`linux status` 输出 distro/installed/rootfs/proot/default 五项 |
| agent 路径 | `linux exec uname -a` → `Linux localhost 6.1.23-android14-… x86_64 Linux` |
| Ubuntu 会话 | `/bin/ls /` 列出 guest rootfs；`echo $PATH` 为 guest 标准 PATH；`apt --version` → `apt 2.8.3 (amd64)` |

### arm64 真机复测（Redmi K40 / Android 16 / LineageOS，2026-10-18）

同一份代码在真机 arm64 上：

| 项目 | 结果 |
|---|---|
| 构建 | `assembleDebug -Pabi=arm64-v8a`（不含 runtime.zip 的轻量包 23 MB） |
| 安装 | Alpine 16 s 装完 |
| Alpine 会话 | `uname -a` → `Linux localhost 4.19.325-cip131-… aarch64 Linux`；`ls /` 正常（无 fork 报错） |
| 包管理 | `apk update` → 走 `mirrors.aliyun.com`（我们写入的镜像）→ `OK: 24059 distinct packages available` |
| ABI 适配 | 真机不显示 x86_64 的 Alpine 警告，发行版顺序保持 Alpine 在前 |

这直接验证了「x86_64 的 fork 限制不影响真机」的推断：arm64 内核没有 `fork` 系统调用，
musl 一律走 `clone`，Android 的 seccomp 策略因此不拦截。

### 两个实测发现（已修）

1. **guest 环境变量重复键**：交互式会话此前把 `guestEnv` 与 `hostEnv` 直接拼给 proot，
   两者都有 `PATH=`，实测 guest 拿到的是宿主 `/system/bin:/system/xbin`，导致
   `ls: command not found`。现改为 guest 侧 `env -i` 重建环境（宿主变量不再泄漏）。
2. **可打印字符输入**：`adb shell input text` / 硬件键盘走 `KeyEvent` 而不是 IME
   `commitText`，此前被丢弃。现从 `event.unicodeChar` 与 `ACTION_MULTIPLE` 的
   `characters` 取值直接写 PTY。

### x86_64 模拟器上的 Alpine 限制（重要）

Alpine 装得上、shell 能起，但**任何外部命令**都报
`can't fork: Function not implemented`。proot 日志已定位：

```
proot info: vpid 1: seccomp SIGSYS: fork(...) = 0x39
```

x86_64 上的 musl 用 `fork(2)` 系统调用实现 `fork()`，而 Android 的 app seccomp 策略
把 `fork(2)` 判为 SIGSYS 拦截（proot 收到后回 ENOSYS）。**arm64 没有 fork 系统调用**
（musl 一律走 `clone`），所以真机 arm64 不受影响；glibc 的 Ubuntu Base 在 x86_64 上
走 `clone`，实测正常。App 里已据此处理：x86_64 设备上发行版列表把 Ubuntu 排前面，
Alpine 卡片显示该限制说明。

## 四、边界与已知限制

- **没有 systemd / D-Bus / GUI**：容器里只有进程和文件，服务类东西要自己起前台进程。
- **伪 root 不是真 root**：`-0` 让 `id` 显示 0，但底层仍是 App 的 uid，
  真改设备权限、mount、raw socket 依然不行（要真 root 请切权限模式）。
- **性能开销**：proot 用 ptrace 做路径翻译，重 IO / 大量 stat 的构建会明显慢于原生
  bionic 工具链。日常命令感知不到。
- **磁盘**：Alpine ≈ 40 MB（解压后），Ubuntu ≈ 130 MB；装之前 UI 会提示体积。
- **seccomp**：部分 syscall 在 Android 上被过滤（故带 `PROOT_NO_SECCOMP=1`），
  极少数程序仍可能异常退出。
- **卸载 App 会带走 rootfs**：它位于 `filesDir`（私有目录）。需要长期保留请自行备份。
- **回滚不重排**：`resize` 保留内容、裁剪/补空行，不做 reflow（与多数移动端终端一致）。

## 五、排错

| 现象 | 处理 |
|---|---|
| 终端里 `bash: not found` | 引擎运行时没装好；标题会显示 `Android (toybox)` 回落 |
| `linux: proot not installed` | 先在 Linux 环境页装任意发行版（会顺带装 proot 载荷） |
| 发行版里没网 | 检查 `/etc/resolv.conf` 是否被改写；App 需要联网权限（已有） |
| `apt` 报 `Release file ... not valid yet` | 设备时间不对；proot 内 `date` 跟宿主机一致 |
| 安装卡在 0% | 镜像不可达；换网络重试，安装是幂等的（临时目录 + 原子发布） |
| 终端显示乱码 | 确认 `TERM=xterm-256color`；App 内固定注入 |

## 七、运行时按本 App 包名重编译（2026-10-18 新增）

### 为什么

Termux 的二进制把 `/data/data/com.termux/files/usr` **编译期写死**：
`bash` 的 `SYS_BASHRC`/`SYS_PROFILE`、`openssl` 的默认配置目录、`git` 的
`git-core`/templates、`python` 的 stdlib 路径都在 ELF 里。

- 文本文件可以运行时改写（`ExtensionManager.rewriteTermuxPaths`）；
- **ELF 里的字符串改不了**：我们的路径
  `/data/data/app.dsh.mobile/files/engine`（37 字符）比 Termux 的（31 字符）长，
  无法等长替换；
- 其余路径可以靠环境变量兜（`OPENSSL_CONF` / `GIT_EXEC_PATH` / `PYTHONHOME`），
  但 **bash 的系统 bashrc 没有环境变量可覆盖** —— 设备上装过 Termux 时，每个交互
  shell 都会打一行 `bash: /data/data/com.termux/files/usr/etc/bash.bashrc: Permission denied`
  （真机实测；模拟器因为该路径不存在而静默，所以早期只在真机暴露）。

### 做法

用 termux-packages 的构建体系，把包名与前缀换成我们自己的，从源码重编
「对路径敏感」的包，再覆盖进从 Termux 收集的运行时：

```
TERMUX_APP__PACKAGE_NAME=app.dsh.mobile
TERMUX__PREFIX_SUBDIR=engine
→ PREFIX = /data/data/app.dsh.mobile/files/engine
```

`scripts/build-runtime-app-prefix.sh` 把整条流程固化下来（宿主 bwrap 造可写 `/data`，
不需要 root）：

```bash
./scripts/build-runtime-app-prefix.sh --abi x86_64 \
    --base-runtime runtime-collected-x86_64.zip --out runtime-app-prefix-x86_64.zip
./scripts/build-runtime-app-prefix.sh --abi arm64-v8a \
    --base-runtime runtime-collected-arm64.zip --out runtime-app-prefix-arm64.zip
```

缺省只重编 `bash`（会递归带出 `libandroid-support` / `libiconv` / `readline` / `ncurses`），
需要更多包时用 `--pkgs "bash openssl git"`。

### 对 termux-packages 打的宿主补丁（都在 `[dsh-patch]` 注释下，脚本幂等应用）

| 位置 | 补丁 | 原因 |
|---|---|---|
| `scripts/build/termux_step_setup_variables.sh` | 构建标记放 `$TERMUX_TOPDIR` | 宿主没有 `/data/data` |
| `scripts/build/termux_step_handle_buildarch.sh` | arch 标记同上 + 判断 `/data` 可写 | 同上 |
| `scripts/properties.sh` | 包名/前缀目录可被环境覆盖；usr-merge 校验默认关闭 | 我们是 `<rootfs>/engine`，不是 usr-merge |
| `scripts/build/toolchain/termux_setup_toolchain_30.sh` | 无 `fuse-overlayfs` 时复制 toolchain | 装它要 root |
| `scripts/build/termux_download.sh` | GitHub archive → `codeload.github.com` | `github.com/.../archive` 常被 429 |
| `packages/ncurses/build.sh` | 去掉 foot 的 terminfo 源 | codeberg 对本网络返回 429 |
| `packages/bash/build.sh` | 不依赖 `termux-tools`；`CC_FOR_BUILD=gcc` | 依赖链会拉 pkg/coreutils 一大串；宿主只有 gcc |

前置：Linux x86_64 宿主、`bwrap`、NDK r30（termux 工具链只认 r30/r23c）、
`gcc/patch/make/zip/unzip/dpkg-deb/python3`。SDK 不需要。

### 一个必须遵守的打包契约：**runtime.zip 里不能有符号链接**

`RuntimeInstaller` 把 zip 条目当普通文件解，链接必须**物化**成真实文件
（`scripts/collect-termux-runtime.sh` 就是这么做的）。用 `zip -y` 保留符号链接会让
安装后的 `lib/libreadline.so.8` 只有 18 字节（内容就是链接目标字符串），启动时报：

```
CANNOT LINK EXECUTABLE ".../lib/libreadline.so.8" is too small to be an ELF executable: only found 18 bytes
```

本脚本的打包步骤刻意**不加 `-y`**（zip 默认解引用）。

### 实测（2026-10-18）

| 项 | x86_64 模拟器 | arm64 真机（Redmi K40 / Android 16） |
|---|---|---|
| 运行时版本 | `v1.3.1-app-prefix-x86_64` | `v1.3.1-app-prefix-arm64` |
| `strings bin/bash \| grep com.termux` | **0** | **0** |
| `bash --version` | 5.3.20 (x86_64-pc-linux-android) | 5.3.20 (aarch64-unknown-linux-android) |
| 交互 shell | 提示符正常、无 bashrc 报错 | 同左（真机装过 Termux，改前每次必报 Permission denied） |
| rc 读取 | 正常（`$PREFIX/etc/bash.bashrc`，且不覆盖调用方传入的 PS1） | 同左 |

因此 `ShellProfiles` 里临时的 `bash --norc` 兜底已删除，恢复 `bash -i`。

### 边界

- **node 仍从 Termux 收集**：重编 node 要连 ICU/OpenSSL 一起交叉编译（数十分钟），
  而它的路径敏感项（openssl 配置、证书）已由环境变量钉死，暂时不重编；
- **扩展（python/git/…）仍用运行时前缀改写**：它们装到 `files/extensions/<id>`
  这类**每个扩展都不同**的目录，编译期固定前缀无意义；
- 重编的包以 API 26 为目标（与 App `minSdk 26` 一致；bash 的 `mblen` 需要 26+）；
- 脚本产出的是「基础运行时 + 覆盖」，不是从零构建全部 19 个包。

## 八、运行时热更新：按 **dsh 版本**拉取（2026-10-18 新增，同日修正语义）

### 先分清三个「版本」（这是第一版跑偏的地方）

| 名字 | 例子 | 谁决定 | 怎么升级 |
|---|---|---|---|
| **APK 版本** | `v1.3.1` | 本仓库 release | 重装 APK |
| **运行时资产标签** | `v1.3.1-app-prefix-arm64` | APK 内置 `assets/runtime.zip` | 随 APK |
| **dsh 版本** | `0.2.0-rc.2` | 上游 `@deepseek-ai/dsh` | **`dsh-update check/pull`** |

`dsh-update` 管的是最后一列：**拉最新 dsh 运行时，不是最新 APK**。第一版只接受
「你给的 zip」，且状态里只有运行时标签，看起来才像在升 App —— 现在改成以 dsh 版本为准。

### 为什么不在手机上直接 `npm install @deepseek-ai/dsh@latest`

Android 兼容补丁是在**构建期**打进 dsh 依赖树的：`openPath/openTextFile` 内联读文件、
旧 WebView polyfill、`koffi`/`node-pty` 打桩、`@deepseek-ai/node-addon-system` 的
flock 兜底（这条缺了会让引擎陷入重启循环）。设备上没有 python3、也不该在现场重跑
这套补丁；直接装上游包 = 装一个起不来的引擎。所以实现是「**拉一份针对该 dsh 版本
预先构建并打过补丁的运行时包**」。

### feed（运行时源）

清单 `runtime-manifest.json` 以 **dsh 版本**为键：

```json
{
  "schema": 1,
  "dsh_version": "0.2.1",
  "built_at": "2026-10-18T02:00:00Z",
  "assets": {
    "arm64-v8a": {"url": "https://…/runtime-arm64-v8a.zip", "sha256": "…", "size": 172961543},
    "x86_64":    {"url": "…", "sha256": "…", "size": 176352302}
  }
}
```

- 默认地址：本仓库 release 的滚动 tag `dsh-runtime-latest`；
- 覆盖方式：环境变量 `DSH_RUNTIME_MANIFEST`，或 `dsh-update check|pull --manifest <url>`
  （自建镜像、镜像站、本地测试都走这个）；
- 清单由 `.github/workflows/dsh-runtime.yml` 产出：每天查一次 npm、有新 dsh 版本就
  用该版本重跑 `scripts/collect-termux-runtime.sh`（打补丁）、双架构构建、发
  `dsh-runtime-<ver>` 归档 release 并更新 `dsh-runtime-latest`。

### 机制

```
filesDir/
  engine/            live（EngineSupervisor 启动的那棵树）
  engine.staging/    解压中
  engine.new/        已就绪的暂存树（带 .runtime-version）
  engine.old/        上一棵（回滚用；下次健康启动后回收）
  .runtime-swap      write-ahead 记录，崩溃后据此恢复
  .runtime-reclaim   engine.old 可回收标记（2 分钟年龄门槛）
```

- `pull`：取清单 → 按 ABI 选包 → 下载（边下边校验 SHA-256 与大小）→ 复用 `stage`
  的校验/解压/发布（zip 结构、ELF ABI、空间、完整性探针）→ 落进 `engine.new`。
  **同版本不重复拉**（省 170MB，也避免换一棵语义相同的树）。
- `apply`：写 write-ahead → 停引擎 → `engine→engine.old`、`engine.new→engine` →
  启动 → 等 `:3080` 健康（90 s）→ 成功；不健康则**自动换回**并重启旧运行时。
- `engine/.runtime-source=external` 让 `RuntimeInstaller` 下次启动**不会**用 APK 内置
  `runtime.zip` 覆盖热更新结果（否则一重启就“回滚”）。
- `rollback` 换回 `engine.old` 再启动；回滚点只保留一次启动，之后回收。

### 给 agent 的入口：`dsh-update` 门脚本

```
dsh-update check [--manifest <url>]     # 本机 dsh / npm 最新 / feed 可装（只读，不下载）
dsh-update pull [dsh-version] [--apply] # 拉最新 dsh 运行时并暂存；--apply 接着换树重启
dsh-update status                       # 含 version（运行时标签）+ dsh（引擎真实版本）
dsh-update stage <zip|url> [sha256]     # 手工指定运行时包（离线/灰度）
dsh-update apply | rollback | restart
```

底层是 app 内 HTTP 桥（`127.0.0.1:3083`，与 notify/scr 同一套 token 鉴权）的
`/runtime/feed`、`/runtime/pull`、`/runtime/stage|apply|rollback|status` 与
`/engine/restart`。动作是**异步 202 + 轮询 status**：apply 会杀掉 agent 所在引擎进程，
所以响应先返回、重启在 app 进程里完成，shell 被杀死也不影响结果。

设置页「运行时更新」一行：显示运行时标签与 dsh 版本；点开可选「检查 dsh 更新」，
有新版时给出「拉取并应用」（= `pull --apply` 的 UI 等价物）。

### 实测（2026-10-18）

| 项 | 环境 | 结果 |
|---|---|---|
| `check` | arm64 真机 + 本地 feed | 打印 live `0.2.0-rc.2` / npm 最新 / feed `0.2.1` / `update_available: true` |
| `pull` | arm64 真机（173 MB 包，adb reverse 本地源） | **19.6 s** 完成下载+校验+解压进 `engine.new` |
| `pull --apply` | arm64 真机 | 26 s 全流程，`apply: ok`，引擎健康、回滚点保留 |
| 同版本保护 | arm64 真机 | `already on latest dsh 0.2.0-rc.2 (feed …)`，不重复下载 |
| 模拟器（第一版） | x86_64 | stage 14 s / apply 4.3 s / rollback 6.3 s；坏包 90 s 超时自动回滚；换树中途被杀可恢复 |

### 边界

- feed 是「按 dsh 版本预先构建」的：npm 上一发新版本，要等 CI 产出对应运行时包才能
  安装（`check` 会同时显示 npm 最新值，能看出这个差距）；
- 运行时包 ~170 MB/架构，`pull` 与 `apply` 期间磁盘峰值约 equal to「下载包 + 新树 +
  旧树」，`pull` 前会检查剩余空间；
- rollback 窗口只有一次启动；之后 `engine.old` 被回收，`rollback` 返回 409；
- 仍是热更新 → 只换运行时，不动 APK、`dsh-home`、`workspaces`、会话。

## 九、WebUI 侧边栏终端（node-pty 桥，2026-10-18 新增）

### 现象

dsh WebUI（App 内 WebView，`127.0.0.1:3080`）侧边栏的终端打不开，报：

```
终端错误: subprocess-local: terminal inspection is unsupported on platform android
```

或者更早的 `node-pty unavailable in this Android build`。两个错误是**两条独立的阻断**：

1. **node-pty 没有 Android 实现**：上游预编译 `.node` 是 glibc/x64 的，bionic 加载不了；
   运行时里那份是构建期空壳，`spawn()` 直接 throw。
2. **进程检查器不认 android**：`dsh-subprocess-local` 把平台限定成
   linux/darwin/win32，android 直接抛错 —— 即使 PTY 能开也会在这里失败。

### 做法

**① 真 PTY 留在 App 进程，引擎通过桥使用它**

```
引擎(node)                     App 进程
node-pty shim  ──HTTP──▶ AgentBridge /pty/{open,write,resize,kill,close,read}
                                └─▶ libdshpty.so（fork/exec + /dev/ptmx）
```

- `PtyBridge.kt`：会话管理 + 每会话 reader 线程 + 512KB 环形缓冲（绝对序号 `seq`），
  `GET /pty/read?id&seq&timeout` 长轮询取增量（base64）；`resolved` 后给 `exit_code/signal`。
- `assets/runtime-overlay/lib/node_modules/node-pty/`：**真** node-pty 实现（CommonJS），
  由 `EngineConfig.applyRuntimeOverlays()` 在每次引擎启动时覆盖进引擎树（只在目标不是
  原生 node-pty 时覆盖，幂等）。`spawn()` 保持**同步**语义（用子 node 进程做一次 HTTP
  open，父进程 `execFileSync` 等它——引擎在 spawn 返回后立刻读取 `terminal.pid`）。
- 会话表从 8 扩到 16 槽（1 引擎 + 7 App 终端 tab + 8 引擎侧）；App 内终端上限仍 7。

**② 让「进程检查」认 android**

`EngineConfig.patchEngineForAndroid()` 在引擎启动时按**内容锚点**扫描
`@deepseek-ai/dsh-subprocess-local/lib/*.js`（文件名带内容哈希，不能写死），把

```js
if (platform === "linux") return new LinuxProcessInspector(arch, internals);
```

改成 `linux || android`。Android 就是 Linux 变体，检查器用 `/proc/<pid>/` 下的
stat/task/fd/syscall，全都存在；`/bin/ps` 只在 darwin 分支和 `foregroundPgid`
（已 try/catch）里用。幂等：锚点消失即跳过，运行时升级后自动按新文件重打。

**③ 默认 shell 指向引擎自带 bash**

引擎用 `process.env.SHELL || userInfo().shell` 决定默认 shell；Android 没有 passwd 条目，
不设就是 `/bin/sh`（toybox）。`EngineConfig.buildEnv()` 现在注入
`SHELL=<engineRoot>/bin/bash`，所以侧边栏拿到的是**真正的 bash**（我们的 PS1、rc、补全）。

### 踩到的三个坑（都在 C 层 / 生命周期）

| 坑 | 现象 | 修法 |
|---|---|---|
| `PR_SET_PDEATHSIG` 绑的是**线程**不是进程 | open 成功但 read 立刻 exited / signal=15，零输出 | 所有 fork 放到常驻 `pty-spawner` 线程上做 |
| fork 后在子进程里 `opendir`/`malloc`（多线程 ART） | 子进程在 exec 前崩在 libsigchain | `close_inherited_fds` 改成不分配内存的 `close(3..1023)` 循环 |
| 子进程里对 SIGSEGV/SIGBUS 等调 `sigaction` | 同上，子进程卡死 | 只复位「普通信号」白名单；崩溃类交给 execve 自动复位 |
| 引擎传的 `cwd` 在本机不存在 | 子进程 `_exit(125)`，终端一开就退 | cwd 不可用时回落到 `dsh-home` 并记日志 |

### 实测（arm64 真机）

- 侧边栏「新建终端」→ 出现 `~ $` 提示符（App 注入的 PS1）；
- 输入 `bash --version` 触发 GNU bash 的输出/用法文本 —— 确认是引擎自带 bash 而非 toybox sh；
- `node -e` 直接调 shim：`spawn(bash)` → 输出 `PTY_OK_2`、resize 生效、`kill('SIGKILL')` →
  `exit` 事件 `{exitCode:0, signal:'SIGKILL'}`；
- App 内终端（TerminalActivity）回归正常，两边共用 PTY 表互不影响。

## 十、原生运行时 ≠ Termux：pkg / apt 为什么用不了（2026-10-18 新增）

### 一句话

App 的原生 shell 用的是**从 Termux 仓库收集的最小闭包**（node、bash、ripgrep、curl
wrapper、pnpm 等），**不是 Termux 的完整 rootfs**。所以 `apt`、`dpkg` 不存在，
原来的 `pkg` 也只是 Termux 的一个脚本壳子，必然失败。

### 具体原因（三条，缺一不可）

1. **运行时里没有包管理器**。`collect-termux-runtime.sh` 只挑运行 dsh 所需的命令闭包，
   从不包含 `apt`/`dpkg`/`apt-utils`（它们会连带 ~100MB 依赖）。
2. **`pkg` 是 Termux 的脚本，路径写死**。它一上来就
   `source /data/data/com.termux/files/usr/bin/termux-setup-package-manager`，
   我们的前缀是 `/data/data/app.dsh.mobile/files/engine` → 立刻失败。
3. **即使把 apt/dpkg 塞进来也没用**。Termux 的 apt/dpkg 编译期前缀写死
   `/data/data/com.termux/files/usr`；dpkg 的 status 数据库、`sources.list`、签名公钥
   都在那个前缀下，Termux 仓库里 `.deb` 的内部路径同样是 `data/data/com.termux/...`。
   装进我们的目录会全部错位 —— 这正是我们重编 bash 时把前缀改成 app 包名的同一个坑。

### 现在能用的两条"装包"路

**① 原生扩展（等价 `pkg install`）**

`engine/bin/pkg` 已换成 App 自己的门脚本（不再是 Termux 的残废壳）：

```
pkg list                 # 21 个扩展：python / openjdk-17 / golang / rust / ruby / php /
                         #   lua / perl / clang / git / archivers / android-buildtools /
                         #   cmake / sqlite / ffmpeg / imagemagick / openssh / android-tools /
                         #   vim / llama-cpp / asr-service
pkg search <关键词>
pkg install <id|name>    # 走 App 扩展中心：下载 Termux .deb → 改写前缀 → 展开到
                         #   <engine>/extensions/<id>/ → 并入 PATH/LD_LIBRARY_PATH
pkg state <id> / pkg where <id>
```

实测：`pkg install archivers` **8.7 秒**完成（下载+解包+激活），`pkg list/search/state`
正常。安装后需要重启引擎（或新开 shell）才能在新 shell 的 PATH 里看到。

**② 真正的发行版包管理器（apk / apt）**

需要依赖解析、系统级工具、apt/apk 生态时，用 proot Linux 环境：

```
linux shell alpine        # 进去后：apk update && apk add python3
linux run alpine apk --version
linux shell ubuntu        # 进去后：apt update && apt install ...
```

实测：Alpine 内 `apk-tools 2.14.4 (aarch64)` 正常运行。

### 顺带修掉的一个真回归：重编的 bash `test -x` 全假

排查 `linux` 门脚本报 `proot: missing` 时发现：我们重编的 bash 5.3.20 里
`[ -x 任意文件 ]` **恒为假**（连 `/system/bin/sh` 都假），但 `[ -e ]` 正常。

- 根因：bionic 的 `faccessat()` 不支持 `AT_EACCESS`（传了返回 EINVAL），而 bash 的
  `sh_eaccess()` 在 configure 探测到 `HAVE_FACCESSAT && AT_EACCESS` 时**只走**
  `faccessat(AT_EACCESS)` → 全部返回失败。
- 影响：所有用 `[ -x ]` 的脚本（门脚本、用户的构建脚本）静默走错分支；`linux` 门脚本
  因此误报 proot 缺失。
- 修法：构建时 `ac_cv_func_faccessat=no`（让 bash 回落到 `access(2)` 分支），
  已写进 `scripts/build-runtime-app-prefix.sh` 并加了注释。重编后的运行时
  `v1.3.2-app-prefix-arm64` 上 `[ -x ]`、`[ -x /system/bin/sh ]` 均正常，
  `linux status` 恢复 `proot: ok`。

### 结论对照表

| 需求 | 用什么 |
|---|---|
| 装 python/git/ffmpeg 等 | `pkg install python`（App 扩展，改写到我们的前缀） |
| 真 apt/apk 依赖解析、系统发行版工具 | `linux shell alpine` / `linux shell ubuntu` |
| 直接抄 Termux 的 `pkg`/`apt` | ❌ 前缀与 dpkg 数据库都不匹配，做不到 |

## 十一、原生包管理器：真 dpkg + Termux 仓库（2026-10-18 新增）

> 上一节说了「原生运行时不是 Termux、`apt`/`pkg` 用不了」。这一节是解法：
> 把 Termux 的 `dpkg`、`proot` 按本 App 的包名**重新交叉编译**，再用 proot 的
> bind 把 Termux 前缀映射到本 App 的运行时目录 —— 于是 Termux 仓库里的 `.deb`
> 可以真装、真卸载、真依赖解析。

### 为什么需要 proot

Termux 的 `.deb` 内部路径全是 `/data/data/com.termux/files/usr/...`，而本 App 的
前缀是 `/data/data/app.dsh.mobile/files/engine`（Android 不允许我们创建别的应用
的数据目录）。直接 `dpkg -i` 会把文件铺到错误位置。做法是：

```
proot -r <pkgroot> -b <engine>:/data/data/com.termux/files/usr \
      -b <engine>:<engine> -b <dsh-home>:/data/data/com.termux/files/home \
      <engine>/bin/dpkg --unpack ...
```

`pkgroot` 是个假 rootfs 骨架（`<files>/pkgroot`），只为让 proot 的 bind 目标存在：
- guest 里的 `/data/data/com.termux/files/usr/bin/vim` → 实际写到 `<engine>/bin/vim`
- 维护脚本 shebang `#!/data/data/com.termux/files/usr/bin/sh` → `<engine>/bin/sh`
- dpkg 本体按 app 前缀编译，admindir 天然是 `<engine>/var/lib/dpkg`

### 组件（全部按包名/前缀重编，不是拷 Termux 的二进制）

| 组件 | 作用 | 关键补丁（都在 `scripts/build-runtime-app-prefix.sh` 里带 `[dsh-patch]`） |
|---|---|---|
| `dpkg` 1.22.6 | 真状态库、文件清单、卸载、``--configure`` | 依赖裁到库（coreutils 等运行时工具单独装）、禁用 `dpkg-perl`/`dpkg-scanpackages` 子包（会拉 perl/clang）、源码换 GitHub 镜像（salsa 对本网络 429） |
| `proot` 5.1.107.96 | 前缀映射（bind） | `src/extension` 补 `#include <string.h>`（NDK r30 严格报错） |
| `libandroid-shmem` | proot 依赖 | 补 `_PATH_TMP`（NDK r30 的 paths.h 没有） |
| `attr` / `libacl` | tar 依赖（可选） | 补 glibc 的 `IFTODT` 宏（bionic 没有） |
| `zstd` | `dpkg-deb` 需要 `libzstd.so.1` | 无 |

### 三个必须踩的坑（都已修）

1. **`bin/sh` 不存在**：Termux 的 bash 包不带 `bin/sh`，而所有维护脚本 shebang 都是
   `.../usr/bin/sh`。运行时 overlay 补一个 `bin/sh` 包装（exec 同目录 bash）。
2. **`dpkg-deb` 要 GNU tar 的选项**：它调 `tar --warning=no-timestamp`，而 Android
   只有 toybox tar（不认 GNU 选项）。overlay 的 `bin/tar` 会过滤 GNU 专有选项再交给
   toybox（只给 dpkg 用，不替代用户自己的 tar）。
3. **`update-alternatives` 造绝对路径链接**：它在 guest 里建
   `/data/data/com.termux/files/usr/bin/vim → ...`，出了 proot 就是悬空链接
   （`vim: inaccessible or not found`）。`pkg` 装完会把这类链接改写成**相对链接**，
   proot 内外都能用；`pkg fixup` 可手动重跑。
   另外运行时 zip 里的符号链接会被 `RuntimeInstaller` 写成普通文件（`bin/sh` 变
   0 字节），所以构建脚本打包前**把所有链接解引用成真文件**（270 个）。

### 用法

```
pkg update                # 拉 Termux 官方仓库索引（Packages.gz，约 1.8MB / 3000 包）
pkg install vim           # 依赖解析 + 下载校验 + dpkg 安装（实测 76s，含 4 个包）
pkg list                  # 读 dpkg 状态库
pkg remove vim
pkg search python
pkg fixup                 # 修复 alternatives 绝对链接（装完自动跑）
```

宿主机构建（复现）：

```
scripts/build-runtime-app-prefix.sh --abi arm64-v8a \
  --base-runtime <collected.zip> --out /tmp/runtime-arm64.zip
```

默认 `PKGS="bash proot dpkg zstd"`（依赖自动递归构建）。

### 与 apt 的关系

`apt` 本体（`pkg` 脚本的官方后端）依赖 gnutls/gcrypt/gpgv/termux-keyring 等 10+ 个包，
还没交叉编译；当前 `pkg` 是「Termux 仓库 + 真 dpkg + 自研依赖解析」的前端，
`install/remove/list/search/info` 已经可用。需要 apt 的完整行为（版本求解、
`apt upgrade`、release 签名校验）时再补 apt 那一层。

## 十二、包名切换为 `com.termux`：完整 Termux 环境 + 内置适配引擎（2026-10-18）

### 为什么改包名

Termux 仓库里的二进制/`.deb` 把 `/data/data/com.termux/files/usr` 写死在 ELF 和包路径里。
只要应用包名不是 `com.termux`，就得靠 proot 做前缀映射（上一节）。把 applicationId 直接
改成 `com.termux` 后：

- `<filesDir>/usr` 就是真正的 `$PREFIX`，`.deb` 原样安装、维护脚本 shebang 原样可跑；
- `apt` / `dpkg` / `pkg` 用 **Termux 官方 bootstrap**，一个包都不用重编；
- 不再需要自编 dpkg/proot/tar 垫片。

代价（必须知道）：**不能与官方 Termux 共存**（同包名不同签名），安装本 APK 前要先卸载
Termux，且 Termux 的旧数据因签名变化无法迁移。应用数据目录也随包名变化，需要迁移。

### 两层运行时（互不覆盖）

| 层 | 内容 | 来源 |
|---|---|---|
| Termux 基础环境 | bash / apt / dpkg / coreutils / sed / tar … | `assets/bootstrap-aarch64.zip` 解到 `<files>/usr`，`SYMLINKS.txt` 重建 1213 个链接 |
| dsh 引擎 | node + `lib/node_modules/@deepseek-ai/dsh` 适配版闭包 + ripgrep | `assets/engine-dsh-aarch64.zip`（**随 APK 打包**）|

node 本身用 Termux 仓库的 `nodejs-lts`（首启 `pkg install`），因为要跟着仓库升级；
**dsh 绝不能用 npm 安装** —— 上游包在 Android/Termux 装不起来，本项目存在的意义就是这份
适配运行时。

### 事故复盘：改包名后 dsh 起不来（已修）

现象：主页空白、`engine.log` 报
`No usable native binding found for node-addon-require-builtin-android-arm64 (auto)`。

原因链：
1. 首启时我让 `BootstrapInstaller` 用 **npm** 装了 `@deepseek-ai/dsh`，npm 把**更新的嵌套
   依赖**（`dsh/node_modules/node-addon-require-builtin`）铺进去，覆盖了适配版里那份能用的
   实现 → `dsh-app-boot` 找不到 android 原生 binding，直接 fatal。
2. 恢复数据后，`dsh-home/storages/workspace.json`、`session_projcache/*.json` 里存的是
   **旧包名的绝对路径**（`/data/user/0/app.dsh.mobile/files/...`）→ 会话恢复时去 mkdir
   旧包目录 → `EACCES` → 主页看起来"起不来"。

修法：
- 引擎只从 APK 资产解压（先删掉可能被 npm 污染的 `lib/node_modules` 再解），彻底不走 npm；
- 新增 `LegacyDataMigrator`：首启把 dsh-home 文本数据里的旧包名路径改写成本包名，并按
  `workspace.json` 重建工作区目录（幂等，写 `.path-migrated-com.termux` 标记）；
- bootstrap 解压后 `restoreExecBits`（ZipInputStream 不保留权限位，否则 `bin/pkg` 不能执行）；
- 运行时资产打包前把所有符号链接解引用成真文件（zip 契约）。

### 现在的能力

```
pkg install vim          # Termux 原生 apt（无 proot、无改写）
apt update && apt upgrade
dsh-update status        # 查看内置引擎版本（引擎随 APK 升级）
linux shell alpine       # 发行版仍走 proot（与 Termux 环境独立）
```

## 十三、dsh-update：引擎层热更新（2026-10-18 恢复，设备实测通过）

包名切到 `com.termux` 后运行时是两层（见上一节）。**热更新只换引擎层**：

```
<files>/usr/lib/node_modules      ← 换
<files>/usr/bin/node, bin/rg      ← 换
<files>/usr/.dsh-engine-version   ← 换
（apt/dpkg/bash/coreutils 等 bootstrap 一个都不碰）
```

### 命令

```
dsh-update status                        # 本地 dsh/node/引擎版本、暂存/回滚状态
dsh-update check [--manifest URL]         # 本地 dsh 版本 vs feed（未发布时退回 npm registry）
dsh-update pull [版本] [--apply]          # 从 feed 下载引擎 tar.gz，校验 sha256 后暂存
dsh-update stage <url|path> [sha256]      # 暂存指定包（离线/内测）
dsh-update apply                          # 备份→换入→重启→健康检查（失败自动回滚）
dsh-update rollback                       # 回到上一份引擎
dsh-update restart                        # 只重启引擎
```

- 版本判断口径和以前一样：**按 `@deepseek-ai/dsh` 版本**，不是 APK 版本；
- `apply` 先把旧层 mv 到 `<prefix>/.engine-prev`（含 bin/node、bin/rg、版本 stamp），
  换入新层后 `pkill` 引擎让守护重启，用 `http://127.0.0.1:3080/` 健康检查；
  新树不健康自动回滚并恢复旧 stamp；
- 产物只含引擎层，所以更新引擎不会破坏 `pkg`/`apt`。

### CI 发布

`.github/workflows/dsh-runtime.yml` 在构建完 runtimes 后额外产出：

```
engine-dsh-aarch64.tar.gz / engine-dsh-x86_64.tar.gz
engine-manifest.json   { dsh_version, engine_version, assets.<arch>.{url,sha256,size} }
```

发布到滚动 release `dsh-engine-latest`（默认 feed）与不可变 `dsh-runtime-<dsh版本>` 归档。
`check` 只走官方 GitHub（直链 → Releases API 找 `engine-manifest.json`），**不查 npm**；还没发布时如实报告 unavailable，不拿 npm 冒充。

### 设备实测（arm64）

```
stage: ok (dsh-engine-0.2.0-rc.2-20261018)
apply: swapped in ...   health: ok (http 401)   apply: ok
rollback: restored previous engine (dsh-engine-2026.10.18)   health: ok
```
