# WebView 配置优化 + 默认隐藏滚动条（2.9.114 / 685）

## 1. 需求

> 参考 `SurgicalNote/.../CnWebView.java`，结合官方文档与网络资料优化 WebView 配置，同时默认隐藏滚动条。

## 2. 参考来源

| 来源 | 取用点 |
|---|---|
| `CnWebView.java`（SurgicalNote） | `setVertical/HorizontalScrollBarEnabled(false)` 隐藏原生滚动条、`OVER_SCROLL_NEVER` 去边缘辉光、`textZoom=100`、`defaultTextEncodingName`、`MIXED_CONTENT_COMPATIBILITY_MODE`、`LOAD_DEFAULT` |
| [WebSettings 官方文档](https://developer.android.com/reference/android/webkit/WebSettings) | 各开关的平台默认值：`mixedContentMode` 默认 `NEVER`、`mediaPlaybackRequiresUserGesture` 默认 `true`、`allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` 默认 `false` |
| [WebView 安全最佳实践](https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion) | 不加载 `file://` 就不要开 file 域跨源访问（XSS → 本地文件读取链） |
| [WebView 性能建议](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content) | 本地/回环页优先用 HTTP 缓存（`LOAD_DEFAULT`），页面自身带 hash 的静态资源命中率高 |

## 3. 改动清单

### 3.1 外观（`WebViewManager.configure()` 开头）

```kotlin
webView.isVerticalScrollBarEnabled = false
webView.isHorizontalScrollBarEnabled = false
webView.overScrollMode = View.OVER_SCROLL_NEVER
webView.setBackgroundColor(0xFFF0F1F5.toInt())   // 页面首帧前的空窗期不闪白
```

布局同时加了 `android:scrollbars="none"`（`activity_main.xml`），双保险：
原生滚动条不走 CSS，必须在 View 层关；`scrollbars=none` 保证 XML 预览与代码行为一致。

### 3.2 settings（在原有 JS/DOMStorage/多窗口/wideViewport 之后追加）

| 项 | 值 | 理由 |
|---|---|---|
| `defaultTextEncodingName` | `utf-8` | 引擎页是 UTF-8，显式声明避免中文站点猜错编码 |
| `textZoom` | `100` | 系统字体缩放会把网页文字一起放大，与 App 自己的「页面缩放」打架；整体缩放交给 viewport |
| `mixedContentMode` | `MIXED_CONTENT_COMPATIBILITY_MODE` | 引擎页是同源 http://127.0.0.1；AI 打开的第三方页若混用协议，默认 `NEVER` 会整块空白 |
| `cacheMode` | `LOAD_DEFAULT` | 本地回环静态资源命中 HTTP 缓存 |
| `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` | `false` | 本 App 不加载 `file://` 页面，显式写死默认值防误改 |
| `mediaPlaybackRequiresUserGesture` | `true` | 防止 AI 生成的媒体在后台自动播放 |
| `setGeolocationEnabled(false)` | `false` | 网页不需要定位 |
| 缩放三连 | 全 `false` | 2.9.113 起无条件禁用（见 `f48da6f`） |

保留原行为：`allowFileAccess=false`、`allowContentAccess=false`（收窄攻击面）、
`setSupportMultipleWindows(true)` + `javaScriptCanOpenWindowsAutomatically=true`（OAuth/`window.open` 依赖它，见类内注释）。

## 4. 真机验证（vivo PA2353 / Android 14，2.9.114 / 685）

- 安装后 `content query content://com.termux.adb/state` → `versionName=2.9.114`；
- 页面正常渲染（截图 2800×1840）；
- 滚动一屏后取右边缘 40px 列：`mean≈250 / std≈21`（纯浅色背景，无原生滚动条竖线）；
- 缩放禁用回归：`I MainActivity: webview zoom disabled: support=false builtIn=false`。

## 5. 已知取舍

- `textZoom=100` 牺牲了「跟随系统字体大小」的无障碍性，换取排版稳定；如果后续要支持，
  应改为读取 `Configuration.fontScale` 后按比例调整页面 `pageScale`，而不是放开 `textZoom`。
- `MIXED_CONTENT_COMPATIBILITY_MODE` 会让 https 页面里的 http 资源显示出来，属于
  可用性优先的选择；若要更严格的安全姿态，可改成 `NEVER` 并接受局部图片不显示。
