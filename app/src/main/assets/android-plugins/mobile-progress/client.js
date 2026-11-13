/** 浏览器端兼容占位；实际进度由 host 插件订阅引擎事件后推送。 */
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
