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
| 回到底部 | 菜单 ⋮ → Scroll to bottom；返回键在回看历史时先回到底部 |
| 新会话 | 顶栏 ＋；选 Android / 已安装发行版 |

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
