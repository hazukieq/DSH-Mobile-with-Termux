# 顶部工具栏默认收起（2.9.111 / 682）

> 历史取证记录：结论仅适用于文中设备、版本和采样范围。当前行为与维护约束见相关使用文档；旧行号和临时文件路径不代表当前工作区。

## 1. 需求

用户：顶部那条「引擎已就绪 · url / 重启 / 隐藏 / 齿轮」默认展开，每次进 App 都要点一次
「隐藏」才能全屏看网页 → 改成**默认收起**，想用按钮时点顶部小把手唤回。

## 2. 实现

| 层 | 改动 |
|---|---|
| 布局 `app/src/main/res/layout/activity_main.xml` | `statusBarRow` 默认 `visibility="gone"`，`handleBar` 默认 `visible`（避免首帧闪一下工具栏再收起） |
| 偏好 `app/src/main/java/app/dsh/mobile/DshPrefs.kt` | 新增 `KEY_TOOLBAR_HIDDEN`，`toolbarHidden(ctx)` **缺省 true**、`setToolbarHidden(ctx, hidden)` |
| 控制器 `app/src/main/java/app/dsh/mobile/webview/NativeChrome.kt` | `bind()` 末尾按偏好 `applyToolbarHidden(...)`；`toggleToolbar()` 改成「切换 + 落库」，可见性操作抽成 `applyToolbarHidden(hidden)` |

语义：**默认收起**；用户的显式选择（点把手唤回 / 点「隐藏」）会被记住，下次启动保持一致。

## 3. 实测（vivo PA2353 平板 / Android 14，2.9.111 / 682）

| 场景 | 结果 |
|---|---|
| `pm clear` 后全新安装首启 | 无顶部工具栏，只有顶部小把手 + 悬浮球 |
| 点小把手 | 工具栏出现（引擎已就绪 · url ／ 重启 ／ 隐藏 ／ ⚙） |
| 点「隐藏」 | 工具栏收起，把手回来 |
| `am force-stop` 后重新启动 | 工具栏仍是收起状态（偏好已持久化） |
| 截图证据 | `/tmp/pad9.png`（默认收起）、`/tmp/pad13.png`（把手唤回后）、`/tmp/pad14.png`（重启后仍收起） |

## 4. 注意

- 工具栏收起时「引擎启动中 Ns」这类状态文案也一并隐藏；启动进度由开屏遮罩承担，不影响观感。
- 把手可拖动（沿用 `setupHandleBar()`），拖丢会自动 clamp 回屏内。
