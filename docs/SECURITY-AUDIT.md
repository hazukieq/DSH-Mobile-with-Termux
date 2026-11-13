# 权限、数据与审计入口

本页按仓库源码描述检查面，不是已经完成的独立安全认证。具体 APK 的合并 Manifest、
签名和内置载荷必须另行核对，历史版本的指纹与体积不能作为当前产物的信任依据。

## 网络与凭据

| 入口 | 当前实现 |
|---|---|
| 引擎 WebUI | 默认 `127.0.0.1:3080`，由引擎管理自己的访问 token |
| AgentBridge | `127.0.0.1:3083`；请求需当前 `X-DSH-Token`，也兼容查询参数 token |
| Shizuku 桥 | 本地端口 3082，能力取决于用户的 Shizuku 授权 |
| LAN WebUI | 显式开关启动端口 3090 的独立代理，使用本次开启生成的访问码；不把引擎改绑 0.0.0.0:3080 |
| 可选本地语义服务 | `LocalModel` 的 llama-server，回环端口 3085 |
| 用户或插件启动的服务 | 监听地址由对应命令决定，不能保证所有服务都只监听回环 |

Bridge token 在进程内生成，写入应用私有 `$PREFIX/.bridge-token`，同时注入引擎环境。
主机可经受权限限制的 `content://com.termux.adb/token` 调试入口读取；该入口还能在应用环境执行命令，
不是只读接口。详见仓库 `docs/ADB-DEBUG-CHANNEL.md`。不要把 token 写入共享存储或公开日志。

模型请求发往用户配置的端点。搜索、网页抓取、TTS、更新检查、模型下载、插件安装、
`pkg`/`apt` 和 agent 执行的命令也可能联网；不能把出站范围描述为只有模型 API。
会话在本机保存不代表会话内容不会被发送给配置的模型或工具服务。

## Manifest 权限

以 `app/src/main/AndroidManifest.xml` 和 APK 合并 Manifest 为准：

| 权限/能力 | 用途 |
|---|---|
| INTERNET、ACCESS_NETWORK_STATE | 网络访问与状态 |
| FOREGROUND_SERVICE、FOREGROUND_SERVICE_SPECIAL_USE | 引擎前台服务 |
| POST_NOTIFICATIONS | 引擎、进度、完成与 LAN 提示 |
| WAKE_LOCK、REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 后台任务及省电配置 |
| SYSTEM_ALERT_WINDOW | 无障碍服务不可用时的应用悬浮窗；无障碍窗口不要求此权限 |
| MANAGE_EXTERNAL_STORAGE、READ_EXTERNAL_STORAGE、WRITE_EXTERNAL_STORAGE | 授权范围内的共享文件访问 |
| MODIFY_AUDIO_SETTINGS | 音量控制 |
| REQUEST_INSTALL_PACKAGES | 拉起系统 APK 安装流程，最终由系统和用户确认 |
| SET_ALARM | 交给时钟应用设置闹钟 |
| 无障碍服务 | 当前界面节点/截图、操作注入、触摸和通知事件缓存 |

没有独立 NotificationListenerService；`GET /notif` 来自无障碍通知事件缓存。
撤销无障碍不影响普通文件操作、分享、媒体播放、终端和引擎控制，具体依赖见 [A11Y-SCOPE.md](A11Y-SCOPE.md)。
Normal 模式使用应用 UID，内置命令、清理与截图回退不会自动尝试 su 或 Shizuku。
Shizuku 模式只走 Shizuku 通道，失败不会回退到 Root；只有显式选择 Root 模式才执行 su。
初始能力展示只检查候选文件，实际 Root 探测需用户主动触发。第三方插件与引擎共享执行权限。

## 导出组件

源码中显式导出的组件如下，依赖库合并结果仍需检查：

| 组件 | 入口与限制 |
|---|---|
| MainActivity | 启动与分享接收，外部输入需按 ShareHandler/WebView 处理逻辑核对 |
| term.TerminalActivity | 终端启动入口 |
| engine.DshDocumentsProvider | SAF 文件访问，受 MANAGE_DOCUMENTS 保护 |
| bridge.AdbDebugProvider | 查询与 exec 调试通道；Manifest 的 DUMP 读权限、代码调用权限检查及全局开关 |
| rikka.shizuku.ShizukuProvider | Shizuku 通道，受 INTERACT_ACROSS_USERS_FULL 保护 |

OnboardingActivity、设置页、引擎服务与结果接收器不导出。
无障碍服务由 BIND_ACCESSIBILITY_SERVICE 绑定要求约束，不是一个任意应用都能启动的控制端点。

## 数据位置

- `files/usr/`（`$PREFIX`）：Termux 运行时、引擎、私有 token 与引擎日志。
- `files/dsh-home/`（`$DSH_HOME`）：配置、凭证、会话、工作区注册表、插件和其他日志。
- 会话/工作区可能经软链外置到共享存储 `Documents/dshdata`、`Documents/DSH` 或
  `Documents/DSH-Mobile`；无所有文件访问权限时可回退到应用外部专属目录。
- 共享存储文件可能在卸载后保留，外部专属目录通常随卸载清理；不要仅凭逻辑路径判断真实落点。
- `GET /storage` / `native storage` 给出 realpath、软链与卸载保留信息。比较工作区归属时也应规范化路径。
- 配置中的 API Key 不应被当作已加密存储；日志和显式截图文件也应纳入清理与权限检查。

## 验证命令

```sh
bash scripts/audit-apk.sh /path/to/app-release.apk
apksigner verify --print-certs /path/to/app-release.apk
sha256sum /path/to/app-release.apk
bash scripts/acceptance.sh --static
```

签名应和你信任的该发布渠道/已安装版本比较；本地构建可能使用发布密钥或 debug 密钥。
APK 载荷可与对应发布的 manifest 核对，不用旧版本的固定散列代替验证。
`android analyze <apk>` 查看包信息；设备截图、布局、装机优先 android CLI。
监听端口、系统日志及调试 provider 查询仍可用 adb shell；抓包结果应注明当时启用的功能、插件和任务。

完整审计应分别覆盖请求鉴权、导出组件、文件/URI 输入、特权调用、第三方插件、
载荷来源、凭证落盘及退出清理，并给出源码位置或命令输出。无法验证的项目应明确记为未验证。
