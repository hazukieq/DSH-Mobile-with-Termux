# dsh-mobile-ui — 把 DSH Web UI 适配成 Android App 风格

> 结论先说：**不需要 fork 整个 DSH 仓库重新构建**。DSH 的 Web 端由「一个 Vite 外壳 + 70 多个
> 运行时 client 插件」组成，官方在 `slots` 契约里明确留了可覆盖的挂载点；因此可以写成
> **一个客户端插件（原版之上的覆盖层）**，保留原版所有组件与状态机，只重排/重绘外观。
> 升级 DSH 时只需跟随类名与少量契约，不用维护整个前端分叉。

本目录是这套方案的实现与实时预览工具。

---

## 1. 现场勘察：DSH Web UI 到底是怎么拼出来的

本机引擎目录 `engine/lib/node_modules/@deepseek-ai/`（发布产物，**没有 monorepo 源码**）：

| 角色 | 包 | 说明 |
| --- | --- | --- |
| Web 入口外壳 | `dsh-web-frontend`（apps/web 的产物） | `dist/index.html` + 一个 633KB 的 `index-*.js`，只含外壳和少量 primitives |
| 客户端模块系统 | `dsh-client-modules` | 扫描所有 loader entry 的 `dsh.client` 声明，拼出 `window.__DSH_BOOT__` 图，在 `/plugins/...` 提供每个插件的 `client.js` |
| 三列布局 | `dsh-client-ui-layout` | `AppFrame` 注册进 `root` slot：左栏（`sidebar`）/中栏（`main`）/右栏（`rightbar`）+ `shell.overlay` 浮层 |
| 会话 | `dsh-client-ui-conversation` / `dsh-client-ui-chat` | 头部 `conversation.header`、正文、`InputBar` 输入框 |
| 侧栏 | `dsh-client-ui-sidebar` | `SidebarRoot`，窄屏时渲染 56px 图标轨 |
| 覆盖契约 | `dsh-cordis-client-runner` | 内含完整 slot 目录（kind/scope/replaceRisk/ownerProps/示例），是**最权威的扩展点文档** |

关键机制：

- **client 插件形态**：`lib/client.js` 是一段 classic script，调用
  `window.__ModuleLoader__.load({ id, factory })` 注册工厂；`factory(require)` 返回
  `{ apply(ctx), inject }`，由 cordis 挂载。支持 `require('react')` 等平台 seed。
- **启动图**：`index.html` 里内联注入 `__DSH_BOOT__`（entries + batches），浏览器端
  `ClientModuleRegistry` 逐条加载 `/plugins/...` 并 mount。**可以在 HTML 层往图里追加一行**，
  这就是本仓库 dev 预览的做法。
- **HMR**：`dsh-client-hmr` 每 500ms stat 每个插件 bundle，变化即通过 `/plugins/events` SSE
  推给浏览器，前端只热替换该插件，无需重启引擎。
- **认证**：`GET /?token=` → 303 + `dsh-auth-<authority hash>` Cookie（Host 绑定、SameSite=Strict）。
- **调试**：App 的 WebView 会把 `console`/`error` 转发到 `engine.log`，可以看到客户端报错。

### 是否"基于原版重写"的三条路

| 方案 | 做法 | 评价 |
| --- | --- | --- |
| **A. 覆盖层插件（本实现）** | client 插件只做三件事：注入 CSS、在 `<body>` 上挂固定顶栏/遮罩、驱动原版状态机（展开侧栏）。组件与数据全部沿用原版 | ✅ 改动小、随原版升级、可随时卸载恢复；缺点：依赖原版 CSS Module 类名（带 hash，但语义后缀稳定） |
| B. slot 覆盖（React 重写局部） | 用官方 slot 的 `replaceRisk: shadows-shipped-ui` 覆盖 `conversation.header` / `conversation.composer.bar` / `sidebar` 等，写 React 组件替换原版 | ✅ 更"正统"，不依赖类名；缺点：要跟随上游 ownerProps/standardProps 契约，工作量与升级风险都高于 A |
| C. fork monorepo 重新 build | 拉源码 `pnpm run build:web` 出 dist 覆盖 | ❌ 本机只有发布产物（无源码、无 vite 链）；每次升级都要重做；APK 更新还会覆盖运行时目录。不建议 |

**推荐 A，必要时对个别区域叠加 B**（例如以后想让头部变成真正的 Material TopAppBar 组件）。

---

## 2. 手机 UI 目标（按 Android App 习惯）

```
┌──────────────────────────────────────────┐
│ ☰   会话标题                    ⓘ        │ ← 顶部 App Bar（52dp，安全区）
├──────────────────────────────────────────┤
│                                          │
│   对话内容（全宽、12px 边距）              │
│                                          │
│                                          │
│   ┌────────────────────────────────┐     │
│   │ 输入框（全宽圆角卡片、16px 字号）  │     │ ← 输入区（贴底 + 安全区）
│   │ ＋  模型/权限          ➤        │     │
│   └────────────────────────────────┘     │
└──────────────────────────────────────────┘
   ☰ → 左侧抽屉（86vw，遮罩、点击外部关闭）
   ⓘ → 顶部信息面板（默认收起；展开显示会话层级/操作/页签）
```

- 侧边栏：由原版 56px 图标轨 → **popup 抽屉**（fixed + translateX + scrim）。
- 顶部信息：原版 conversation header 默认 `display:none`，点 ⓘ 以 fixed 面板下挂展开。
- 两个按钮分列顶栏左右两端。
- 输入区：全宽、圆角、16px、44dp 触控目标、底部 safe-area。
- 右栏（文件/终端/浏览器）：全屏覆盖。
- 桌面布局完全不受影响（全部样式带 `html[data-dsh-mobile]` 前缀，且宽度/UA 判断后才启用）。

## 3. 目录

```
mobile-ui/
├── package.json          # dsh.bundle.patch + dsh.client(platform: web)
├── cordis.patch.yml      # 往 profile 插入 loader entry
├── lib/index.js          # host 半边：空插件（只为让 loader 能 import）
├── lib/client.js         # 构建产物（勿手改）
├── src/runtime.js        # DOM 逻辑：顶栏、抽屉、信息面板、诊断
├── src/mobile.css        # 手机样式（全部带 html[data-dsh-mobile] 前缀）
├── build.mjs             # 组装 client.js（runtime + css → loader 包装）
├── dev-proxy.mjs         # 实时预览：代理 3080 并在 boot 图里临时挂载本插件
└── .probe/               # 勘察用：真实 index.html / boot 图样本
```

## 4. 实时预览（不重启引擎、不动当前会话）

```bash
node dev-proxy.mjs        # 监听 http://127.0.0.1:3000，转发 127.0.0.1:3080
```

打开 `http://127.0.0.1:3000/`（或带上引擎日志里的 `?token=`）。代理会：

1. 反向代理 HTTP + WebSocket（`/api/remote.mux`）到真实引擎；
2. 在 `index.html` 注入引导脚本，把 `dsh-mobile-ui` 一行追加进 `__DSH_BOOT__`；
3. 在 `/dev/mobile-client.js` 现场构建插件 bundle；
4. 把页面里的布局快照 POST 到 `/__diag`，写进 `.diag/last.json`（可直接检查几何数据）；
5. 监听 `src/*` 变更 → 自动刷新预览。

## 5. 安装进 profile（正式生效）

```bash
# 1) 把本目录装进 web profile（pnpnm 会同步 package.json 的 bundles）
node <engine>/lib/bin.js plugin --profile web add /绝对路径/mobile-ui
# 2) 重启引擎（设置 → 重启引擎）使 loader entry 生效
```

卸载：`dsh plugin --profile web remove dsh-mobile-ui` + 重启引擎（回退原版 UI）。

## 6. 维护注意

- `src/mobile.css` 里的 `.pI_x6G_*`、`.wSkVaW_*`、`.uV2eYG_*`、`.EvIC1a_*`、`.hHd-Xa_*`
  是 DSH 0.2.0-rc.2 的 CSS Module 类名（hash 前缀随构建变、语义后缀稳定）。
  升级 DSH 后先看 `engine/lib/node_modules/@deepseek-ai/dsh-client-ui-{layout,conversation,chat,sidebar}/lib/client.js`
  里的 `*_module_css_default` 映射表，用新类名替换即可。
- 更稳的长期路线：把顶栏/信息面板改造成 slot 覆盖（方案 B），类名变化就不再影响。

## 7. 故障记录与调试闭环

### 7.1 「白屏 / 会话显示不出来」（v0.1.1 → v0.1.2，已修）

症状：只剩顶部 52dp 的 App Bar，中间会话正文、输入框全都不见了，整页看起来像崩溃。

根因（**不是 JS 报错，是 CSS grid 自动排布**）：

1. `.pI_x6G_frame` 是 `display:grid`，三列靠**隐式自动排布**落位；
2. 本层把 `.pI_x6G_sidebarCol` 改成 `position: fixed` 变成抽屉 → 该元素**脱离 grid 布局流**；
   右栏在收起态又是 `display:none`，同样不占位；
3. 于是流内只剩 `.pI_x6G_centerCol` 一个元素，自动排布把它放进**第一列**——而第一列被我们
   特意设成 `0px`（`grid-template-columns: 0 minmax(0,1fr) 0`）；
4. 结果：`centerCol` 宽 0，`wSkVaW_root` / `uV2eYG_card` 全 0 宽，输入框被顶到 y≈1800 的屏幕外。

修复：给三列**显式命名轨道**（`grid-column: 1 / 2 / 3`），不再依赖自动排布；中列补 `min-width: 0`。
诊断数据（`.diag/last.json`）里能直接看到判据：坏态 `centerCol` 宽 0、`frameGrid` 却是 `0px 360px 0px`；
修好后 `centerCol` 360 宽、`uV2eYG_root` 落在视口底部。

**给后来者的一句话**：只要让某个 grid 子项 `position: fixed` / `display: none`，就必须同时给其余
子项显式 `grid-column`，否则它们会被自动排布整体左移。

### 7.2 抽屉「刚打开又自己关上」（v0.1.2）

`toggleSidebar()` 把状态交给原版 layout 服务，`data-sidebar-collapsed` 由 React 稍后才更新。
`sync()` 每 600ms 跑一次 `reconcileDrawer()`，可能在属性还没翻转时就判定「侧栏已收起 → 关抽屉」。
现在点按后写入 `drawerGraceUntil = now + 900ms`，宽限期内不 reconcile。

### 7.3 安全区（safe-area）

原版 `index.html` 的 viewport 是 `width=device-width, initial-scale=1`，**没有 `viewport-fit=cover`**，
所以 `env(safe-area-inset-*)` 在 App 的 WebView 里恒为 0。当前 App 的 WebView 本身已经在状态栏下方，
几何上是对的；只有把本页面单独丢进全屏/沉浸式 WebView 时才需要改 meta 加 `viewport-fit=cover`，
否则 `--dshm-safe-top` 拿不到刘海高度（顶栏会被状态栏压住）。

### 7.5 抽屉里点「搜索」「设置」立刻被关掉（v0.1.3）

原实现是「只要点在 `.hHd-Xa_regionArea` 里就延迟 60ms 关抽屉」。但原版把**搜索框、行内操作菜单、
设置弹层**都渲染在同一个 region 里（`.bhn1Oq_searchSlot` / `_searchButton` / `_searchInput` /
`_rowActions`），所以点「搜索」的那一刻抽屉就关了 —— 搜索框还没展开就跟着抽屉一起消失；设置同理。

现在改成**白名单 + 正向命中**：

- `DRAWER_CHROME_SELECTOR`：抽屉自身控件（logo 行、panelList、新建会话、settings 区、
  `searchSlot`/`searchButton`/`searchInput`/`clearButton`/`headerActions`/`rowActions`/`iconButton`、
  `input`/`textarea`/`[role=dialog|menu|listbox]`）—— 点到**不关**；
- `DRAWER_NAV_SELECTOR`：只有 `_sessionRow` / `_searchResultRow` 这类真正会跳转的行才关抽屉。

故意做成「正向命中」：上游哪天改类名，失败模式是**不再自动关抽屉**（用户点遮罩即可），
而不是把侧边栏里每一次点击都吞掉。

`.diag/last.json` 新增 `interaction` 字段：`lastCloseReason`（`scrim` / `nav-row` / `reconcile`）
和最近一次点击的 `lastClick`（带 `skip: chrome | not-a-row`）。以后再出现「又关掉了」，
能一眼看出是哪条路径关的。

### 7.6 调试闭环

1. `node dev-proxy.mjs`（3000 → 3080，注入插件 + 诊断上报）；
2. 手机打开 `http://127.0.0.1:3000/?token=<引擎日志里的 token>`；
3. 页面把几何快照 POST 到 `/__diag`，写 `.diag/last.json`（rects / frameGrid / DOM outline），
   JS 报错写 `.diag/log.txt`；
4. 改 `src/*` 后 `node build.mjs`：页面轮询 `/__ver`，rev 变化会自动 reload。

### 7.7 右侧栏在手机上「打不开」（v0.1.8）

先说结论：**不是接线错了，是原版在窄屏把右栏宽度算成 0**。

`dsh-client-ui-layout` 里：

```js
function computeColumns(viewport, sidebar, rightbar, collapsedWidth = 56) {
  const s = sidebar === 0 ? collapsedWidth : clampWidth(sidebar, 264, 420);
  const available = viewport - s - 400;                       // 360 - 56 - 400 = -96
  const r = rightbar === 0 || available < 300 ? 0 : Math.min(available, ...);  // → 0
}
// frame:
gridTemplateColumns: `${cols.sidebar}px minmax(${cols.rightbar === 0 ? 0 : 400}px, 1fr) minmax(0px, ${rightbarMax}px)`
"data-rightbar-collapsed": cols.rightbar === 0
```

所以 `ctx.layout.openRightbar(kind, true)` 执行后 `layoutInfo.rightbarShown = true`，
但 `cols.rightbar` 仍为 0 → `data-rightbar-collapsed` 立刻被重新置上 → 按该属性 `display:none`
的皮肤永远打不开。**这是窄屏的既有限制，不是 bug，但皮肤必须绕开它。**

绕法（本层做法）：

1. 用一个自己的开关 `html[data-dshm-pane="open"]` 决定呈现，完全不看
   `data-rightbar-collapsed`；
2. `layout.openRightbar(kind, true)` 只当「告诉原版有 track」用（它是 report 语义，
   不是命令；`panels.openRightbar` 只改 `layoutInfo`，不碰列宽计算）；
3. 真正放内容要 `ctx.sidebarRight.openTab(kind)`，而且必须在 session surface 挂载之后
   （`openTab → require()` 会在 "no session surface is mounted" 抛错）——所以同 kind 重试
   3 次再换下一个候选 kind；
4. 面板用 `top: var(--dshm-bar-total)` 而不是 `padding-top`：`position: fixed` 下子元素的
   百分比高度按视口解析，用 padding 会把面板自己的 tab 条顶到顶栏底下藏起来。

可用 kind（本机实测）：`guide, subagentchat, changes-review, plan, files, text, terminal`。

### 7.8 设置对话框「没适配 / 被拉变形」（v0.1.8）

设置页是 `dsh-client-ui-settings-general` 渲染的 `role="dialog"[aria-modal="true"]`（类名前缀
`VOzbGW_`，正文 section 是 `_WvWnq_section`）。踩了两个坑：

1. **桌面双栏壳**：`VOzbGW_nav` 固定 188px + `VOzbGW_content` 吃剩下的宽度。360dp 上正文只剩
   **172px**，section 被挤成 **3457px 高**的窄条 —— 这就是「被拉变形」。
   → 把 nav 重排成**顶部横向可滑动 tab 条**（`navList` 改 row + `overflow-x: auto`），
   content/options/section 全部 `width: 100%` + `min-width: 0`。
2. **flex 撑高**：内容算成 1245px 而面板只有 598px，直接越出屏幕且不滚动。
   → 面板 `overflow: hidden`，content `display:flex; column; min-height: 0`，
   `VOzbGW_options` 自己 `overflow-y: auto`。**`min-height: 0` 是这里的关键**，
   没有它 flex 子项不会收缩到内容高度以下。

最后让它「真·整屏」：`top: 0` 覆盖到底，并在 `[data-dshm-modal="open"]` 期间隐藏顶栏
（设置自带「关闭」按钮），观众看到的才是一整页 Android 设置。

### 7.9 诊断字段速查（`.diag/diag.jsonl`）

每次上报都会带：

| 字段 | 用途 |
| --- | --- |
| `version` | 插件版本（对不上就是页面没重载） |
| `interaction.frameAttrs` | 原版 frame 的 `data-sidebar-collapsed` / `data-rightbar-collapsed` / `-fullscreen` |
| `interaction.lastCloseReason` | 抽屉是被 `scrim` / `nav-row` / `reconcile` 关的 |
| `interaction.clickTrace` | 最近 8 次点击（tag/class/aria-label/文本） |
| `paneApi` | 右栏接线解析结果：`layout`、`sidebarRight` 是否可调用、`kinds` 实际列表、`open`、`modal` |
| `dialogs[]` | 每个模态的 class、rect、computed style、DOM outline（用于写精准 CSS） |
| `rects` / `frameGrid` / `outline` | 主布局几何快照 |
### 7.10 顶部面板：浮层被压 / 被裁、面板太矮（v0.1.11）

三件事凑在一起，表现就是「浮层太怪」：

1. **层级**：原版把菜单/浮层渲染进 `.pI_x6G_overlayLayer`，而它有 `z-index: 20`；
   皮肤自己的 chrome 是右栏 30 / 遮罩 34、40 / 顶栏 50 —— 于是任何从顶部面板弹出的菜单
   都被压在下面。现在手机模式把这一层提到 **300**，单个浮面（menu/popover/dropdown）**900**。
2. **裁切**：面板原来是 `overflow-y: auto`。CSS 里一个轴非 `visible` 时另一轴会被算成 `auto`，
   所以面板内弹出的菜单会被面板自己的滚动框切掉一半。现在手机模式 `overflow: visible`，
   面板高度也已经接近整屏，不需要滚动。
3. **高度**：`max-height` 从 `64vh` → `calc(100dvh - var(--dshm-bar-total) - 12px)`。

### 7.11 顶部菜单「内联 chips」尝试与回退（v0.1.11 → v0.1.12 已回退）

需求是「⋮ 菜单项直接展开在面板里，不弹浮层」。当时的实现是：把浮层里的项提取成一排 chips
插进 ⓘ 面板，原浮层加 `.dshm-menu-source`（`visibility: hidden`）保留动作 handler。

**结论：被否决并已整体回退（v0.1.12）**，原因两条：

1. 观感太丑（一排圆角 chips 挤在面板的 tab 行下面）；
2. **误伤**：`[class*="_menu"]` 这个选择器把**输入框左边 + 的弹出菜单**
   （`_surface_ri079_1 _3e4SsG_menu`）也一起抓走并隐藏了 —— 表现就是「输入框左边的 + 点击不了」。
   这是选择器覆盖面过宽 + 「隐藏原浮层」这套做法本身的脆弱性。

保留下来的是同批的另外两个修复（v0.1.9/v0.1.10，都有实测依据）：
浮层所在 `.pI_x6G_overlayLayer` 提到 z-index 300、单个浮面 900；顶部面板 `max-height` 提到
`calc(100dvh - 顶栏 - 12px)`、`overflow: visible`（原 `overflow-y: auto` 会连带把另一轴变 auto，
面板内的浮层会被自己的滚动框裁掉）。

**教训**：皮肤里任何「按类名通配覆盖所有浮层」的做法都要收窄到具体锚点
（例如只在 `.wSkVaW_header` 之内），否则必然影响输入框、下拉、右键菜单这些同类结构。

### 7.12 浮层几何诊断（v0.1.13）

每次上报都带 `floats[]`：所有可见浮层的 `cls / rect / z / inPanel / offscreenBottom`。
`offscreenBottom: true` 表示浮层底部越出视口——「点了 + 什么都不出现」时先看这个字段，
再决定是层级问题还是定位问题。

### 7.13 引擎重启后预览打不开（401）

`dsh web` 的启动 token 每次进程启动都会变（`engine.log` 里那行 `?token=`）。
引擎重启后旧标签页会一直 401（`dsh web authentication required`），页面也不再有上报。
用 `engine.log` **最后一行**的 token 重新打开 `http://127.0.0.1:3000/?token=<新 token>` 即可；
注意 `dsh-mobile-ui` 的预览本身不需要重启（代理只转发）。
### 7.14 设置页细节（开关变形、文字挤压、按钮出界）v0.1.14

三个问题都源于「通配规则打到了原版组件」：

1. **开关变成圆球**：原版开关是 `<button role="switch">`，CSS 写死 `36×20`、
   `border-radius: 999px`。之前为了加大触控区写了
   `[role='dialog'] button { min-height: 40px }` —— 开关被撑成 `36×40`，配上 999px
   圆角就成了圆球（里面白点是 `.thumb`）。
   → 删掉这条通配；改为显式钉住 `[role='switch'] { width: 36px; height: 20px;
   min-height: 0; flex: 0 0 auto }`。**触控尺寸要按控件类型分别给，不能对 button 一刀切。**
2. **「打开配置文件」出界并压住 ✕**：`.VOzbGW_actions` 实测 **0 宽**，按钮溢出面板右缘。
   → header 改 flex：`actions` `flex: 1 1 auto; min-width: 0; overflow-x: auto`，
   `close` `flex: 0 0 auto; margin-left: auto`，并把 ✕ 加到 `40×40` 触控。
3. **文字挤在一起**：设置行是「标签列 + 控件列」，360dp 下标签被压成 4 行窄条。
   → 对话框内 `*_row { flex-wrap: wrap; row-gap: 8px }` + 子项 `min-width: 0`，
   控件放不下就掉到下一行；`[role='dialog'] button { white-space: nowrap }` 避免
   「添加插件」折成两行。

实测行高（同一屏，修前 → 修后）：`94.3 → 80.3`、`72.3 → 68.3`、`130.3 → 120.3`，
面板 `[0,0,360,650]` 整屏、导航横向 tab 条 `40` 高。

同时把对话框的 DOM 上报深度从 6 提到 9、节点预算 80 → 320，直接覆盖到
「行 → 标签/控件」这一层，后续做同类细节不必再靠通配猜。
### 7.15 一键回退（三层保险）v0.1.15

皮肤把界面弄坏时，恰恰是「它自己的设置页也点不到」的时候，所以回退不能只做一个按钮。
本版做了三层，从最方便到最可靠：

**第 1 层：设置页里的按钮**（日常用）

- 注入位置：`.VOzbGW_options` 的第一个子节点（设置内容最上方，不用滚动就看得见）；
- 内容：`移动端界面（dsh-mobile-ui vX）` + 说明 + `回退到原版界面` 按钮；
- 点击 → `rollback('settings-button')`：写 `localStorage['dsh-mobile-ui:disabled']='1'` →
  `dispose()` 拆除 CSS/顶栏/监控 → 上报 `rollback:*` → `location.reload()`。
- 重载后 `apply()` 一开始就 `skinDisabled()` 命中并直接 return —— **不注入任何 CSS/JS**，
  页面就是原版。
- React 重渲染会抹掉注入行，`sync()`（≤600ms）会补回来。

**第 2 层：URL 开关**（界面点不到时用）

- `?dshm=off` → 写标志 + 去掉参数 + 重载，立即回原版；
- `?dshm=on` → 清标志 + 重载，恢复皮肤。
- 预览地址已有 `?token=`，所以要写成 `?token=xxx&dshm=off`。
- 这一层**不依赖任何 UI**，只要 JS 还能跑就有效。

**开关是主机侧的，不是浏览器本地的（v0.1.17，重要）**

一开始标志只存在 `localStorage`。这在 App 的 WebView 里是**没用的**：WebView 没有地址栏，
皮肤一旦把页面弄坏，用户既点不到设置页按钮、也改不了 URL —— 正是「陷入两难」。
所以开关搬到了主机侧：

| 路由（主机半 `lib/index.js` 注册） | 作用 |
| --- | --- |
| `GET /__mobile-ui/state` | `{ disabled }` —— 客户端启动时先问这里 |
| `GET /__mobile-ui/rollback` | 写 `$DSH_HOME/.dsh-mobile-ui-off`，之后一律原版 |
| `GET /__mobile-ui/restore` | 删标志，恢复皮肤 |

- 标志是**引擎级全局**的：在 Edge 的预览里点一次回退，App 的 WebView 下次加载也是原版。
- 客户端 `apply()` 现在先 `fetch('/__mobile-ui/state')`（1.2s 超时、失败即「放行」），
  命中 `disabled` 就直接不注入任何 CSS/JS。
- 也可以用 curl 直接救场：`curl http://127.0.0.1:3080/__mobile-ui/rollback`，
  恢复 `.../restore`。不需要 token，因为引擎只监听回环地址，而这组路由只翻一个 UI 偏好。
- `localStorage` 标志仍然保留，作为同浏览器内的快速短路（两层取并集）。

**不做自愈看门狗（用户决定，v0.1.16 已移除）**

曾经试过「centerCol 宽度 < 40px 持续 8 秒就自动回退」，已按用户要求删除。理由记在这里，
避免以后又被加回来：

- 正常 UI 也会短暂出现窄列（首帧、切标签、动画），阈值/时长只能拍脑袋；
- 误判一次就是把好端端的界面偷偷换回原版，比它要防的故障更让人困惑；
- 真要救场，第 2 层 URL 开关已经足够可靠，且**由用户决定**何时触发。

所以现在回退只有两个入口：设置页按钮 + URL 开关。

**边界说明**

- 标志存在 `localStorage`，**按 origin 独立**：预览（`:3000`）和应用本体（`:3080`）互不影响，
  所以在预览里回退不会改到正式环境。
- 彻底卸载仍是 CLI：`dsh plugin --profile web remove dsh-mobile-ui` + 重启引擎。
