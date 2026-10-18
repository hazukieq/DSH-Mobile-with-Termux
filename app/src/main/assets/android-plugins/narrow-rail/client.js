
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
      "  /* 引擎自己的侧边栏不再参与布局：收起时完全隐藏，展开也只是一闪而过（会立刻被自动折叠）。 */",
      "  [class*=\"_root\"]:has(> [class*=\"_logoRow\"])[class*=\"_collapsed\"] {",
      "    display: none !important;",
      "  }",
      "  [class*=\"_sidebarCol\"]:has([class*=\"_root\"][class*=\"_collapsed\"]) {",
      "    width: 0 !important;",
      "    min-width: 0 !important;",
      "    flex: 0 0 0 !important;",
      "    overflow: hidden !important;",
      "  }",
      "  /* 拉取会话列表时临时隐藏侧栏，避免闪一下 */",
      "  html.dsh-collecting [class*=\"_sidebarCol\"] {",
      "    visibility: hidden !important;",
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


    /* ------------- 引擎侧栏：永远不占布局，且不保持展开 ------------- */
    function railRoot() {
      return document.querySelector('[class*="_sidebarCol"] [class*="_root"]:has(> [class*="_logoRow"])')
        || document.querySelector('[class*="_root"]:has(> [class*="_logoRow"])');
    }
    function isCollapsed(root) {
      return !!root && String(root.className || '').indexOf('_collapsed') >= 0;
    }
    /* 引擎用内联 grid-template-columns 给左侧留 56px；这里无条件把第一轨压到 0，
       展开/收起都不再占用内容宽度（展开态由自动折叠逻辑立即收掉）。 */
    function syncFrameColumns() {
      var frame = document.querySelector('[class*="_frame"]');
      if (!frame || !frame.style) return;
      var parts = (frame.style.gridTemplateColumns || '').trim().split(/\s+/);
      if (parts.length >= 3 && parts[0] !== '0px') {
        parts[0] = '0px';
        frame.style.gridTemplateColumns = parts.join(' ');
      }
    }
    /* 自动折叠：只要引擎把侧栏展开（搜索/快捷键等），立刻点它的「收起侧边栏」。 */
    function autoCollapse() {
      if (window.__dshCollecting) return;
      var root = railRoot();
      if (!root || isCollapsed(root)) return;
      var btn = document.querySelector('button[aria-label="收起侧边栏"]')
        || document.querySelector('button[class*="_toggle"]');
      if (btn) btn.click();
    }
    function tick() { syncFrameColumns(); autoCollapse(); }

    /* ------------- 会话列表（给原生抽屉二级页用） ------------- */
    function collectSessions() {
      var toggle = document.querySelector('button[aria-label="打开侧边栏"]');
      if (!toggle) { try { DshAndroid.onSessions('[]'); } catch (e) {} return; }
      window.__dshCollecting = true;
      document.documentElement.classList.add('dsh-collecting');
      toggle.click();
      setTimeout(function () {
        var rows = [].slice.call(document.querySelectorAll('[class*="_sessionRow"]'));
        // innerText 在侧栏被 visibility:hidden 时返回空串（我们在收集期间隐藏了侧栏），
        // 必须用 textContent + 结构选择器取值。
        var out = rows.map(function (r) {
          var titleEl = r.querySelector('span[class*="_title"]');
          var timeEl = r.querySelector('span[class*="_time"]');
          return {
            title: (titleEl ? titleEl.textContent : '').trim(),
            meta: (timeEl ? timeEl.textContent : '').trim(),
            selected: String(r.className || '').indexOf('_selected') >= 0
          };
        });
        var close = document.querySelector('button[aria-label="收起侧边栏"]');
        if (close) close.click();
        document.documentElement.classList.remove('dsh-collecting');
        window.__dshCollecting = false;
        try { DshAndroid.onSessions(JSON.stringify(out)); } catch (e) {}
      }, 260);
    }
    function openSession(index) {
      var toggle = document.querySelector('button[aria-label="打开侧边栏"]');
      if (!toggle) return false;
      window.__dshCollecting = true;
      document.documentElement.classList.add('dsh-collecting');
      toggle.click();
      setTimeout(function () {
        var rows = [].slice.call(document.querySelectorAll('[class*="_sessionRow"]'));
        if (rows[index]) rows[index].click();
        var close = document.querySelector('button[aria-label="收起侧边栏"]');
        if (close) close.click();
        document.documentElement.classList.remove('dsh-collecting');
        window.__dshCollecting = false;
      }, 220);
      return true;
    }
    function apply() {
      var cleanup = function () {};
      if (!document.getElementById(STYLE_ID)) {
        var style = document.createElement("style");
        style.id = STYLE_ID;
        style.textContent = STYLE_TEXT;
        (document.head || document.documentElement).appendChild(style);
        cleanup = function () { try { style.remove(); } catch (e) {} };
      }

      // 从 webview 侧给原生抽屉回传会话列表 / 打开会话
      window.__dshNative = window.__dshNative || {};
      window.__dshNative.requestSessions = collectSessions;
      window.__dshNative.openSession = openSession;

      var mo = null;
      if (window.MutationObserver) {
        mo = new MutationObserver(function () { tick(); });
        var frame = document.querySelector('[class*="_frame"]');
        var rail = document.querySelector('[class*="_root"]:has(> [class*="_logoRow"])');
        if (frame) mo.observe(frame, { attributes: true, attributeFilter: ['style', 'class'] });
        if (rail) mo.observe(rail, { attributes: true, attributeFilter: ['class'] });
      }
      var timer = setInterval(tick, 250);
      tick();

      return function () {
        cleanup();
        try { if (mo) mo.disconnect(); } catch (e) {}
        try { clearInterval(timer); } catch (e) {}
      };
    }

    exports.apply = apply;
    return module.exports;
  },
});
