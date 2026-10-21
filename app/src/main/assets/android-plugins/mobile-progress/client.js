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
    var POLL_MS = 250
    var HEARTBEAT_MS = 1500
    var lastKey = ''
    var lastPostAt = 0
    var prevRunning = null

    function txt(el) {
      return ((el && el.textContent) || '').replace(/\s+/g, ' ').trim()
    }

    function transcriptRoot() {
      return document.querySelector('[class*="EvIC1a_column"]') ||
        document.querySelector('[class*="wSkVaW_scrollBody"]')
    }

    function sessionKey() {
      var t = document.querySelector('#dshm-title')
      var title = txt(t)
      if (title) return title
      var row = document.querySelector('[class*="session"][class*="active"]') ||
        document.querySelector('[class*="P3OORG_session"]')
      return txt(row) || document.title || 'default'
    }

    /**
     * 当前这一轮的完整「思考链路」：从最后一个用户消息开始，按 DOM 顺序拼接
     * 所有思考正文与工具步骤。流式输出时文本不断变长 → 浮窗就能实时刷新。
     */
    function buildChain() {
      var root = transcriptRoot()
      if (!root) return ''
      var kids = Array.prototype.slice.call(root.children)
      var start = -1
      for (var i = 0; i < kids.length; i++) {
        if (kids[i].querySelector && kids[i].querySelector('[class*="userRow"]')) start = i
      }
      if (start < 0) start = 0
      var parts = []
      for (var j = start; j < kids.length; j++) {
        if (j > start) {
          var k = kids[j]
          if (k.querySelector && k.querySelector('[class*="userRow"]')) break
        }
        var thinks = kids[j].querySelectorAll ? kids[j].querySelectorAll('details[class*="_think"]') : []
        Array.prototype.forEach.call(thinks, function (d) {
          var body = d.querySelector('div')
          var t = txt(body) || txt(d)
          t = t.replace(/^思考\s*/, '')
          if (t) parts.push(t)
        })
        var labels = kids[j].querySelectorAll ? kids[j].querySelectorAll('[class*="O_Ebla_label"]') : []
        Array.prototype.forEach.call(labels, function (el) {
          var t = txt(el)
          if (t) parts.push('· ' + t)
        })
      }
      return parts.join('\n\n').slice(0, 4000)
    }

    function isRunning() {
      var btn = Array.prototype.slice.call(document.querySelectorAll('button')).find(function (b) {
        var l = (b.getAttribute('aria-label') || '') + ' ' + txt(b)
        return /停止|Stop|Cancel/i.test(l)
      })
      return !!btn
    }

    function post(kind, detail) {
      try {
        if (!window.DshNative || !window.DshNative.progress) return
        window.DshNative.progress(JSON.stringify({
          kind: kind,
          title: sessionKey(),
          session: sessionKey(),
          detail: detail || '',
        }))
      } catch (e) { /* bridge absent or page detached */ }
    }

    function tick(force) {
      var running = isRunning()
      var chain = buildChain()
      var kind
      if (running) {
        kind = chain ? 'thinking' : 'working'
      } else if (prevRunning === true) {
        kind = 'done'
      } else if (!force) {
        prevRunning = running
        return
      } else {
        kind = 'idle'
      }
      var key = kind + '|' + sessionKey() + '|' + chain
      var now = Date.now()
      var heartbeat = running && now - lastPostAt >= HEARTBEAT_MS
      if (force || key !== lastKey || heartbeat) {
        lastKey = key
        lastPostAt = now
        post(kind, chain)
      }
      prevRunning = running
    }

    /** v1.3.77：App 切后台时原生会调用它，强制立刻上报当前快照。 */
    function poke() {
      lastKey = ''
      tick(true)
    }

    var timer = null
    function apply() {
      try { window.__dshMobileProgressPoke = poke } catch (e) {}
      if (timer === null) timer = window.setInterval(function () { tick(false) }, POLL_MS)
      return function () {
        if (timer !== null) { window.clearInterval(timer); timer = null }
      }
    }

    exports.apply = apply
    return module.exports
  },
})
