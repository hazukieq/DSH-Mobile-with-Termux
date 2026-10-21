/**
 * dsh-mobile-progress（browser 侧占位，v1.3.97）
 *
 * 进度改由 host 侧插件订阅 agent/assistant-stream / session/event 直推 Android，
 * 不再从 WebUI DOM 抓取。这里只保留 poke 占位，避免 MainActivity 的
 * evaluateJavascript 调用报错。引擎浏览器插件必须走 __ModuleLoader__ 包装。
 */
window.__ModuleLoader__.load({
  id: 'dsh-mobile-progress',
  factory: () => {
    var module = { exports: {} }
    var exports = module.exports

    try {
      if (!window.__dshMobileProgressPoke) window.__dshMobileProgressPoke = function () {}
    } catch (e) { /* bridge absent */ }

    function apply() {}

    exports.apply = apply
    return module.exports
  },
})
