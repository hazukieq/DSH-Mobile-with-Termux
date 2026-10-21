/**
 * dsh-mobile-progress (v1.3.68)
 *
 * 把当前会话的「思考 / 工具步骤 / 运行状态」通过 DshNative.progress() 报给原生，
 * 供后台通知/悬浮窗展示。纯 DOM 观察，不读屏、不需要无障碍；桌面浏览器里
 * 没有 DshNative 时自动 no-op。
 */
window.__ModuleLoader__.load({
  id: 'dsh-mobile-progress',
  factory: () => {
    var module = { exports: {} }
    var exports = module.exports
    var POLL_MS = 800
    var HEARTBEAT_MS = 4000
    var lastKey = ''
    var lastPostAt = 0
    var prevRunning = null

    function txt(el) {
      return ((el && el.textContent) || '').replace(/\s+/g, ' ').trim()
    }

    function pickTitle() {
      var el = document.querySelector('#dshm-title') ||
        document.querySelector('[class*="titleCurrent"]') ||
        document.querySelector('[class*="wSkVaW_title"]')
      return txt(el).slice(0, 80)
    }

    function isRunning() {
      var btn = Array.prototype.slice.call(document.querySelectorAll('button')).find(function (b) {
        var l = (b.getAttribute('aria-label') || '') + ' ' + txt(b)
        return /停止|Stop|Cancel/i.test(l)
      })
      return !!btn
    }

    function lastThinking() {
      var list = document.querySelectorAll('details[class*="_think"]')
      if (!list.length) return ''
      var d = list[list.length - 1]
      var body = d.querySelector('div')
      var t = txt(body) || txt(d)
      return t.replace(/^思考\s*/, '').slice(0, 220)
    }

    function lastStep() {
      var labels = document.querySelectorAll('[class*="O_Ebla_label"]')
      if (!labels.length) return ''
      return txt(labels[labels.length - 1]).slice(0, 200)
    }

    function post(kind, detail) {
      try {
        if (!window.DshNative || !window.DshNative.progress) return
        window.DshNative.progress(JSON.stringify({
          kind: kind,
          title: pickTitle(),
          detail: detail || '',
        }))
      } catch (e) { /* bridge absent or page detached */ }
    }

    function tick(force) {
      var running = isRunning()
      var think = lastThinking()
      var step = lastStep()
      var detail = think || step
      var kind
      if (running) {
        kind = think ? 'thinking' : (step ? 'tool' : 'working')
      } else if (prevRunning === true) {
        kind = 'done'
        detail = think || step || ''
      } else {
        prevRunning = running
        return
      }
      var key = kind + '|' + detail
      var now = Date.now()
      var heartbeat = running && now - lastPostAt >= HEARTBEAT_MS
      if (force || key !== lastKey || heartbeat) {
        lastKey = key
        lastPostAt = now
        post(kind, detail)
      }
      prevRunning = running
    }

    /** v1.3.77：App 切后台时原生会调用它，强制立刻上报当前快照（否则要等下一次轮询）。 */
    function poke() {
      lastKey = ''
      tick(true)
    }

    var timer = null
    function apply() {
      try { window.__dshMobileProgressPoke = poke } catch (e) {}
      if (timer === null) timer = window.setInterval(tick, POLL_MS)
      return function () {
        if (timer !== null) { window.clearInterval(timer); timer = null }
      }
    }

    exports.apply = apply
    return module.exports
  },
})
