
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
      "  /* v1.3.0 第二轮：原生顶栏 + 原生抽屉接管导航，收起态直接隐藏整条图标栏。",
      "     抽屉条目通过 click 引擎自己的按钮驱动 WebUI，所以不需要侧边栏图标。 */",
      "  [class*=\"_root\"]:has(> [class*=\"_logoRow\"])[class*=\"_collapsed\"] {",
      "    display: none !important;",
      "  }",
      "  /* 收起态外层还有一列固定宽度的 sidebarCol 会留下左侧空白，一并压到 0 */",
      "  [class*=\"_sidebarCol\"]:has([class*=\"_root\"][class*=\"_collapsed\"]) {",
      "    width: 0 !important;",
      "    min-width: 0 !important;",
      "    flex: 0 0 0 !important;",
      "    overflow: hidden !important;",
      "  }",
      "}",
    ].join("\n");

    /* 引擎布局用内联 grid-template-columns（56px minmax(0,1fr) ...）给左侧栏留位，
       CSS 无法只改第一轨且不破坏右侧栏宽度，所以这里直接改内联样式：
       收起态把第一轨从 56px 改成 0px；展开后引擎会自己写回 56px，我们不干预。 */
    function syncFrameColumns() {
      var frame = document.querySelector('[class*="_frame"]');
      if (!frame || !frame.style) return;
      var collapsed = !!document.querySelector(
        '[class*="_sidebarCol"] [class*="_root"][class*="_collapsed"]'
      );
      if (!collapsed) return;
      var cur = frame.style.gridTemplateColumns || '';
      var parts = cur.trim().split(/\s+/);
      if (parts.length >= 3 && parts[0] !== '0px') {
        parts[0] = '0px';
        frame.style.gridTemplateColumns = parts.join(' ');
      }
    }

    function apply() {
      var cleanup = function () {};
      if (!document.getElementById(STYLE_ID)) {
        var style = document.createElement("style");
        style.id = STYLE_ID;
        style.textContent = STYLE_TEXT;
        (document.head || document.documentElement).appendChild(style);
        cleanup = function () {
          try { style.remove(); } catch (e) { /* 卸载阶段忽略 */ }
        };
      }

      // 布局帧与侧栏状态的任何变化都重新对齐第一轨宽度
      var frame = document.querySelector('[class*="_frame"]');
      var rail = document.querySelector('[class*="_root"]:has(> [class*="_logoRow"])');
      var mo = null;
      if (window.MutationObserver) {
        mo = new MutationObserver(function () { syncFrameColumns(); });
        if (frame) mo.observe(frame, { attributes: true, attributeFilter: ['style', 'class'] });
        if (rail) mo.observe(rail, { attributes: true, attributeFilter: ['class'] });
      }
      var timer = setInterval(syncFrameColumns, 700);
      syncFrameColumns();

      return function () {
        cleanup();
        try { if (mo) mo.disconnect(); } catch (e) { /* ignore */ }
        try { clearInterval(timer); } catch (e) { /* ignore */ }
      };
    }

    exports.apply = apply;
    return module.exports;
  },
});
