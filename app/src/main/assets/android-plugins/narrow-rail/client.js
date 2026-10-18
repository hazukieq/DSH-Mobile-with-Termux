
/**
 * [dsh-android] 窄屏侧边栏收纳（v1.3.0）。
 *
 * 症状：手机竖屏里左侧常驻一条 56px 的图标栏（logo/新建/插件/自动化/记忆/工作区/
 * 搜索…），占用宝贵的横向空间，而且这些入口大多数时候用不到。
 *
 * 目标（用户要求）：**收起时只保留左上角一个图标**，点击才展开侧边栏。
 *
 * 做法：引擎的侧边栏根元素带 `_collapsed` 类（收起）与 `_logoRow` 子元素
 * （logo + 展开按钮，按钮本身 aria-label="打开侧边栏"，点击由引擎处理）。
 * 窄屏收起态下把根宽度压到 0、隐藏除 logoRow 外的所有子元素，把 logoRow
 * 固定到左上角做成悬浮按钮；展开后（不再有 `_collapsed`）样式整体失效，
 * 引擎原样显示侧边栏。类名带构建哈希，这里用后缀属性选择器匹配。
 *
 * 顶部偏移 44px：WebView 顶部压着 App 的 40dp 半透明工具栏（引擎自己的页面
 * 从工具栏下方开始），悬浮按钮需要落在工具栏下面才可见/可点。
 *
 * @module dsh-android-narrow-rail/client
 */

window.__ModuleLoader__.load({
  id: "dsh-android-narrow-rail",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    const STYLE_ID = "dsh-android-narrow-rail-style";

    const STYLE_TEXT = [
      "@media (max-width: 720px) {",
      "  /* 收起态：整条图标栏占位归零，内容区拿回横向空间 */",
      "  [class*=\"_root\"]:has(> [class*=\"_logoRow\"])[class*=\"_collapsed\"] {",
      "    width: 0 !important;",
      "    min-width: 0 !important;",
      "    flex: 0 0 0 !important;",
      "    overflow: visible !important;",
      "    background: transparent !important;",
      "    border-right: 0 !important;",
      "    box-shadow: none !important;",
      "  }",
      "  /* 除 logoRow 外的入口全部隐藏（新建/插件/自动化/记忆/工作区/搜索…） */",
      "  [class*=\"_root\"]:has(> [class*=\"_logoRow\"])[class*=\"_collapsed\"] > *:not([class*=\"_logoRow\"]) {",
      "    display: none !important;",
      "  }",
      "  /* 唯一保留的图标：左上角悬浮按钮（点击 = 引擎的「打开侧边栏」） */",
      "  [class*=\"_root\"]:has(> [class*=\"_logoRow\"])[class*=\"_collapsed\"] > [class*=\"_logoRow\"] {",
      "    position: fixed !important;",
      "    top: calc(env(safe-area-inset-top, 0px) + 44px) !important;",
      "    left: 8px !important;",
      "    z-index: 900 !important;",
      "    width: 40px !important;",
      "    height: 40px !important;",
      "    margin: 0 !important;",
      "    display: flex !important;",
      "    align-items: center !important;",
      "    justify-content: center !important;",
      "    border-radius: 12px !important;",
      "    background: color-mix(in srgb, var(--dsw-alias-bg-layer-2, #1b1b1d) 90%, transparent) !important;",
      "    box-shadow: 0 1px 6px rgba(0, 0, 0, 0.28) !important;",
      "    -webkit-backdrop-filter: blur(8px);",
      "    backdrop-filter: blur(8px);",
      "  }",
      "  [class*=\"_root\"]:has(> [class*=\"_logoRow\"])[class*=\"_collapsed\"] > [class*=\"_logoRow\"] button {",
      "    width: 100% !important;",
      "    height: 100% !important;",
      "    border-radius: 12px !important;",
      "  }",
      "}",
    ].join("\n");

    function apply() {
      if (document.getElementById(STYLE_ID)) return function () { /* 幂等 */ };
      var style = document.createElement("style");
      style.id = STYLE_ID;
      style.textContent = STYLE_TEXT;
      (document.head || document.documentElement).appendChild(style);
      return function () {
        try { style.remove(); } catch (e) { /* 卸载阶段忽略 */ }
      };
    }

    exports.apply = apply;
    return module.exports;
  },
});
