# 竖屏与平板 UI 适配：客户端插件

> 面向 Android 手机竖屏（约 386 CSS px 宽）与平板竖屏的布局适配。全部以**客户端插件**实现：
> 不修改引擎源码、不抢占 DOM 事件、不与其他 UI 插件冲突，引擎升级也不会覆盖。
>
> 插件清单：`narrow-sidebar`（侧栏全宽）、`narrow-settings`（设置竖排）、
> `narrow-rightbar`（右侧面板全屏）、`narrow-welcome`（首启欢迎页断点）、
> `mobile-ui`（平板方向门控：平板只在竖屏启用移动布局，横屏回落桌面 UI）。

## 1. 问题：不是「挤」，是「宽度算成 0」

引擎 `@deepseek-ai/dsh-client-ui-layout` 用 CSS Grid 排三列，列宽由 `computeColumns()` 计算：

```js
const s = sidebar === 0 ? collapsedWidth : clampWidth(sidebar, 264, 420);
const available = viewport - s - 400;          // 固定给中心对话区预留 400px
const rightbar  = rightbar === 0 || available < 300
  ? 0
  : Math.min(available, clampWidth(rightbar, 300, viewport * 0.7));
return { sidebar: s, center: Math.max(0, viewport - s - rightbar), rightbar };
```

手机竖屏下 `viewport` 只有约 386px。带入公式：

| 场景 | 计算 | 结果 |
|---|---|---|
| 侧栏展开 + 对话区 | `386 - 280 = 106` | 对话区被压成 106px，文字逐字换行 |
| 打开右侧面板 | `available = 386 - 56 - 400 = -70 < 300` | **右栏宽度直接为 0**（那一列根本没渲染） |

所以「查看更改 / 代码差异」在手机上不是显示不全，而是**面板所在的列宽度被算成了 0**。

设置面板则是另一处：`ui-settings-general` 的面板固定 `width: 800px; max-width: calc(100vw - 48px)`，
左导航固定 `width: 188px; flex: none` → 手机上内容区只剩约 150px，标签被压到 47px 逐字竖排。

## 2. 为什么做成插件

DSH 的 WebUI 本身支持**客户端插件**（`dsh.client` 机制）：

- 包是标准 npm 包：`package.json` 声明 `dsh.client`（`platform: "web"` + 可选 `inject`），
  并提供 `exports["./client"]`；
- 引擎的 `dsh-client-modules` 会扫描宿主 Loader 的 entries，把浏览器端 bundle 注入页面；
- bundle 以经典 script 加载，通过 `window.__ModuleLoader__.load({ id, factory })` 注册，
  `id` 必须等于包名。

这种方式的优点：

- **零源码改动**：引擎升级不会覆盖我们的逻辑；
- **天然隔离**：每个插件只给自己定位到的元素打属性、只在自己的 `<style>` 里写规则；
- **可卸载**：移除插件文件即可完全恢复引擎原样。

这些插件都在 APK 的 `assets/android-plugins/` 下，安装后由 app 部署到引擎目录，并在
`android-overlay.yml` 里插入对应 entry。

## 3. 插件一：`narrow-sidebar`（侧栏全宽）

**行为**：竖屏 + 侧栏展开时，侧栏占满整行、对话列让位；横屏/宽屏完全不动。

- **定位**（不依赖哈希类名）：按无障碍标签找折叠按钮
  （`收起侧边栏` / `Collapse sidebar`），向上找到第一个 `display: grid` 的祖先 → 那才是 AppFrame；
  按钮所在列即侧栏列，`nextElementSibling` 即中心列。
- **生效条件**：`innerWidth <= 900 && innerHeight > innerWidth`（必须是竖屏）。
- **做法**：给侧栏列打 `data-android-narrow-sidebar-col`（CSS `grid-column: 1 / -1`），
  给中心列及其后的**列**打 `data-android-narrow-yield-col`（CSS `display: none`）。
- **细节**：判定「列」时会跳过 `position: absolute/fixed` 的元素与引擎自标的
  `data-shell-overlay` 浮层，避免误伤弹窗层。

## 4. 插件二：`narrow-settings`（设置面板窄屏重构）

**行为**：视口 ≤ 720px 时，设置面板改为竖向堆叠 —— 导航变成顶部可横滑的横条，
内容区拿满整宽；宽屏不动。

- **定位**：优先用引擎自标的对话框语义 `[role="dialog"][aria-modal="true"]`，
  再从中找 `<nav>` 语义标签（导航列）与同层最大的兄弟（内容列）。
- **兜底**：若未来版本改掉 role，则退回「浮层内 + 含关闭按钮 + 有 nav」的结构判据。
- **硬门槛**：必须满足「在浮层内」且「含关闭按钮」——**缺一即放弃**。
  这两条是踩坑后的防御：早期只看「按钮多 + 有大兄弟」，把主界面 AppFrame 误判成设置面板，
  结果把主界面改坏成黑屏。
- **做法**：面板 `flex-direction: column`、宽度 `calc(100vw - 24px)`；
  导航 `flex-direction: row` + `overflow-x: auto`；每个导航项 `flex: none; white-space: nowrap`；
  内容列 `width: 100%` + `overflow-x: hidden`。

## 5. 插件三：`narrow-rightbar`（右侧面板全屏化）

**行为**：窄屏下，右侧面板（查看更改 / 轨迹等）**在打开时**提升为整屏浮层；关闭态与宽屏完全不动。

- **定位**：`[data-sidebar-right-panel]` **且带** `[data-sidebar-right-open]`
  —— 后者只在打开时出现，因此关闭态绝不会被接管（不会凭空冒出面板）。
- **做法**：把面板从 0 宽的网格列里提出来：
  `position: fixed; inset: 0; width: 100vw; height: 100vh; z-index: 60`；
  同时把 `--dsh-sidebar-width` 归零、对 `[data-dockkit-host=dock]` 强制
  `visibility: visible; transform: none` —— 抵消引擎在窄屏全屏模式下把 dock 平移出屏幕的位移。

## 6. 插件四：`narrow-welcome`（首启欢迎页断点）

**行为**：WebUI 的「欢迎使用 / 新建会话」首启对话框在平板宽度下不再被当成窄屏全屏铺满；
断点从 720px 提高到 **1100px**，仅在真正窄屏时走移动布局。

- 平板（≥600 CSS px）下把 `[role="dialog"]` 包成居中卡片（640px 宽、20 圆角、阴影），
  工作区选择器对话框固定 `height: min(72dvh, 760px)`，避免文件树被压成 0 高。
- 只作用于首启对话框，不碰主界面与设置。

## 7. 插件五：`mobile-ui`（平板方向门控）

**行为**：平板（`isTabletScreen` ≥ 600 CSS px）只在**竖屏**启用 `mobile-ui`；
旋转到横屏时回落到原生桌面 UI 并重载页面。

- 依据：平板横屏有足够宽度，套移动布局反而浪费空间；手机不受影响。
- 监听 `resize`/`orientationchange`，跨过宽度阈值时 reload 一次（加防抖，避免抖动重载）。

## 8. 调试与验证

**运行时日志**：插件的关键判定会用 `[dsh-android]` 前缀写入 WebView 控制台，
app 会把它转发到引擎日志（`$DSH_HOME/../engine/engine.log`）。例如：

```
[dsh-android] narrow-sidebar: 已接管（侧栏全宽，隐藏 2 个对话列；视口 386x777）
[dsh-android] narrow-settings: 已接管设置面板（视口 386x777）
[dsh-android] narrow-rightbar: 已接管右侧面板（视口 386x777）
[dsh-android] narrow-welcome: welcome dialog centred (viewport 1280x800)
[dsh-android] mobile-ui: tablet landscape -> desktop UI
```

**测试**：仓库内测试覆盖判定逻辑与 DOM 结构变化（`node scripts/probes/_test_narrow_*.mjs`；welcome / mobile-ui 另有独立探针）：

| 测试 | 覆盖 |
|---|---|
| `_test_narrow_sidebar.mjs` | 30 项：竖屏接管 / 横屏不接管 / 转屏恢复 / dispose 零残留 / 节点重建迁移 |
| `_test_narrow_jsdom.mjs` | 26 项：jsdom 真实 DOM，含右栏、overlay 浮层不误伤 |
| `_test_narrow_settings.mjs` | 36 项：面板定位（role/nav）、overlay 不误判、主界面不被误判 |
| `_test_narrow_rightbar.mjs` | 28 项：仅打开态接管、关闭态不动、面板重建迁移 |

**排障清单**：

1. 先看引擎日志里有没有 `[dsh-android] narrow-*` —— 没有说明插件没加载或被条件挡住；
2. 检查 `engine/android-plugins/<pkg>/` 下三个文件是否齐全（`package.json` / `index.js` / `client.js`）；
3. 检查 `android-overlay.yml` 里是否正确插入了对应 entry（`name` 指向 `index.js`）；
4. 重启引擎后再看（插件在引擎启动时加载）。

## 7. 边界

- 桌面模式（横屏 1280px）**完全不受影响**，保留引擎自带的完整分栏与拖拽；
- 宽度阈值：侧栏插件 900px、设置插件 720px、右栏插件 900px；
- 只改布局（宽/高/方向/溢出），不动颜色、字号与间距，避免与主题类插件冲突。
