
/**
 * [dsh-android] 窄屏欢迎页（onboarding「开始使用」）适配。
 *
 * 症状（真机截图 2026-10-19）：手机竖屏里那张「开始使用 / 登录 DeepSeek 账号，
 * 或添加 API Key」卡片**左右超出屏幕被裁切**，说明文字一行到底被切掉。
 *
 * 根因（引擎源码 + 构建后 CSS 取证）：账号插件的欢迎页是**按桌面设计**的：
 *   .xxx_welcome{width:100%;max-width:2200px;height:100%}
 *   .xxx_welcome .xxx_copy{width:520px;position:absolute;left:clamp(64px,41.667vw - 300px,200px)}
 *   .xxx_welcomeIllustration{width:1247px;...}
 * 窄屏（约 393 CSS px）下 520px 的绝对定位文案块必然溢出，右侧被裁。
 *
 * 修法：窄屏断点内把欢迎页改成**正常文档流的居中列**——文案不再绝对定位、
 * 宽度自适应、插画隐藏；宽屏（>1100px 桌面）完全不动。类名带构建哈希，
 * 这里用「后缀属性选择器」（[class*="_welcome"] 等）跨版本匹配。
 *
 * v1.3.55：断点从 720px 提到 1100px。平板竖屏可视宽约 817 CSS px，按 720 断点走
 * 的是桌面排版（copy 绝对定位 520px + 1247px 插画），第一次初始化的欢迎/填密钥
 * 对话框在平板上因此显得别扭、被裁；817px 与桌面 1200px+ 之间没有「真桌面」布局。
 *
 * 注册约定同 narrow-settings：经典 script 加载 + __ModuleLoader__.load，id 必须
 * 等于 package.json 的 name。
 *
 * @module dsh-android-narrow-welcome/client
 */

window.__ModuleLoader__.load({
  id: "dsh-android-narrow-welcome",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    const STYLE_ID = "dsh-android-narrow-welcome-style";

    const STYLE_TEXT = [
      "@media (max-width: 1100px) {",
      "  /* 欢迎页容器：变成可滚动的居中列，禁止横向溢出 */",
      "  [class*=\"_welcome\"]:not([class*=\"_welcomeIllustration\"]) {",
      "    box-sizing: border-box !important;",
      "    position: relative !important;",
      "    width: 100% !important;",
      "    max-width: 100vw !important;",
      "    height: auto !important;",
      "    min-height: 100% !important;",
      "    padding: 24px 16px !important;",
      "    display: flex !important;",
      "    align-items: center !important;",
      "    justify-content: center !important;",
      "    overflow: hidden !important;",
      "  }",
      "  /* 文案块：从 absolute:520px 改回正常流 */",
      "  [class*=\"_welcome\"] > [class*=\"_copy\"] {",
      "    position: static !important;",
      "    width: auto !important;",
      "    max-width: 100% !important;",
      "    transform: none !important;",
      "    z-index: 1 !important;",
      "  }",
      "  [class*=\"_welcome\"] > [class*=\"_copy\"] h1 {",
      "    font-size: 30px !important;",
      "    line-height: 38px !important;",
      "    white-space: normal !important;",
      "  }",
      "  /* 主按钮：占满文案宽度 */",
      "  [class*=\"_welcome\"] > [class*=\"_start\"] {",
      "    width: auto !important;",
      "    margin-top: 28px !important;",
      "  }",
      "  [class*=\"_welcome\"] > [class*=\"_start\"] > * { width: 100% !important; }",
      "  /* 桌面尺寸插画（1247px 绝对定位）：窄屏隐藏，避免撑出横向空白 */",
      "  [class*=\"_welcome\"] [class*=\"_illustration\"],",
      "  [class*=\"_welcome\"] [class*=\"_welcomeIllustration\"] { display: none !important; }",
      "",
      "  /* 通用兜底：任何 modal 都不许宽过可视区，并且弹窗容器按 border-box 计算 */",
      "  /* 根容器按 border-box 计算，否则 padding 会把 100% 宽度撑出视口 */",
      "  [role=\"presentation\"]:has(> [role=\"dialog\"][aria-modal=\"true\"]) {",
      "    box-sizing: border-box !important;",
      "  }",
      "  /* ⚠️ 账号插件的欢迎弹窗把描述文案设成了 white-space:nowrap（桌面单行设计），",
      "     窄屏下 690px 的文本塞进 363px 的卡片 → 右侧被裁。强制换行 + 允许断词。 */",
      "  [role=\"dialog\"][aria-modal=\"true\"] [class*=\"_description\"],",
      "  [role=\"dialog\"][aria-modal=\"true\"] [class*=\"_content\"] {",
      "    white-space: normal !important;",
      "    overflow-wrap: anywhere !important;",
      "    word-break: break-word !important;",
      "    min-width: 0 !important;",
      "  }",
      "}",
      "",
      "/* v1.3.57：平板上「预览版说明」这类文档型 modal 会被上游渲染成整页白板（内容顶到左边、",
      "   按钮贴右上，下方大片空白）。平板宽度 >=600px 时改成居中卡片，桌面上游布局不动。 */",
      "@media (min-width: 600px) and (max-width: 1100px) {",
      "  html body div[role=\"dialog\"][aria-modal=\"true\"][class*=\"jLrgrW_dialog\"] {",
      "    position: fixed !important;",
      "    top: 50% !important;",
      "    left: 50% !important;",
      "    right: auto !important;",
      "    bottom: auto !important;",
      "    transform: translate(-50%, -50%) !important;",
      "    width: min(92vw, 640px) !important;",
      "    max-width: min(92vw, 640px) !important;",
      "    height: auto !important;",
      "    max-height: 82dvh !important;",
      "    border-radius: 20px !important;",
      "    box-shadow: 0 18px 60px rgba(0, 0, 0, 0.35) !important;",
      "    overflow: auto !important;",
      "  }",
      "  html body div[role=\"dialog\"][aria-modal=\"true\"] div[class*=\"jLrgrW_content\"] {",
      "    width: 100% !important;",
      "    box-sizing: border-box !important;",
      "  }",
      "}",
    ].join("\n");

    function apply() {
      var existing = document.getElementById(STYLE_ID);
      if (existing) {
        existing.textContent = STYLE_TEXT;
        return function () { /* 已存在：保留样式，由下一次 apply 更新 */ };
      }
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
