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
    var turnStart = 0

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
     * 当前这一轮的完整「思考链路」：按时间顺序输出带类型标签的步骤。
     * - [思考] 模型的 reasoning 片段
     * - [工具] 工具组标题（如「已访问网页并搜索网页」）
     * - [命令] / [步骤] 每个 flowItem（工具调用与输出摘要）
     * - [回复] 最终回答的末尾片段
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
      var lines = []
      var seen = {}

      function push(line) {
        var t = (line || '').replace(/\s+/g, ' ').trim()
        if (!t) return
        // 工具参数里的 JSON（提问卡片等）不直接展示，抽出 question/label 之类的可读字段
        if (t.charAt(0) === '{' || /^\[\s*[{"]/.test(t)) {
          try {
            var o = JSON.parse(t)
            var label = o.question || o.label || o.title || ''
            if (label) { lines.push('[提问] ' + String(label).slice(0, 200)) }
          } catch (e) { /* 不是完整 JSON：丢弃 */ }
          return
        }
        if (/"options"|"multi_select"|"selected"/.test(t) && t.length > 200) return
        t = t.replace(/^(运行命令|执行了命令)(?![:：])/, '$1: ')
        t = t.replace(/^(网页获取)(?![:：])/, '$1: ')
        if (t.length > 300) t = t.slice(0, 300) + '…'
        if (seen[t]) return
        seen[t] = true
        lines.push(t)
      }

      for (var j = start; j < kids.length; j++) {
        if (j > start) {
          var kj = kids[j]
          if (kj.querySelector && kj.querySelector('[class*="userRow"]')) break
        }
        var k = kids[j]

        // 工具/过程组：标题 + 组内每个 flowItem 按顺序摘下
        var groups = k.querySelectorAll ? k.querySelectorAll('[class*="O_Ebla_root"]') : []
        Array.prototype.forEach.call(groups, function (g) {
          var titleEl = g.querySelector('[class*="O_Ebla_title"]')
          var title = txt(titleEl)
          if (title) push('[工具] ' + title)
          var flow = g.querySelectorAll('[class*="EvIC1a_flowItem"]')
          Array.prototype.forEach.call(flow, function (fi) {
            var t = txt(fi)
            if (!t) return
            t = t.replace(/^(运行命令|执行了命令|执行命令)(?![:：])/, '$1: ')
            t = t.replace(/^(网页获取)(?![:：])/, '$1: ')
            if (/^思考/.test(t)) push('[思考] ' + t.replace(/^思考\s*/, ''))
            else if (/^(运行命令|执行了命令|执行命令)/.test(t)) push('[命令] ' + t)
            else if (/^网页获取/.test(t)) push('[网页] ' + t)
            else push('[步骤] ' + t)
          })
        })

        // 组外的思考块（genui 的 details 形式）
        var thinks = k.querySelectorAll ? k.querySelectorAll('details[class*="_think"]') : []
        Array.prototype.forEach.call(thinks, function (d) {
          var body = d.querySelector('div')
          var t = (txt(body) || txt(d)).replace(/^思考\s*/, '')
          if (t) push('[思考] ' + t)
        })

        // 最终回答：取该轮最后一个 markdown 文本块的尾部
        var md = k.querySelectorAll ? k.querySelectorAll('[class*="_markdown_"], [class*="markdownPreview"]') : []
        if (md.length) {
          var last = txt(md[md.length - 1])
          if (last && !/^思考/.test(last)) push('[回复] ' + last.slice(-400))
        }
      }
      var out = lines.join('\n')
      if (out.length > 4000) {
        out = out.slice(out.length - 4000)
        var nl = out.indexOf('\n')
        if (nl >= 0) out = out.slice(nl + 1)
      }
      return out
    }

    function isRunning() {
      var btn = Array.prototype.slice.call(document.querySelectorAll('button')).find(function (b) {
        var l = (b.getAttribute('aria-label') || '') + ' ' + txt(b)
        return /停止|Stop|Cancel/i.test(l)
      })
      return !!btn
    }

    function post(kind, detail, elapsed) {
      try {
        if (!window.DshNative || !window.DshNative.progress) return
        window.DshNative.progress(JSON.stringify({
          kind: kind,
          title: sessionKey(),
          session: sessionKey(),
          detail: detail || '',
          elapsed: elapsed || 0,
        }))
      } catch (e) { /* bridge absent or page detached */ }
    }

    function tick(force) {
      var running = isRunning()
      if (running && prevRunning !== true) turnStart = Date.now()
      var elapsed = running ? Date.now() - turnStart : 0
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
        post(kind, chain, elapsed)
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
      try { window.__dshMobileBuildChain = buildChain } catch (e) {}
      if (timer === null) timer = window.setInterval(function () { tick(false) }, POLL_MS)
      return function () {
        if (timer !== null) { window.clearInterval(timer); timer = null }
      }
    }

    exports.apply = apply
    return module.exports
  },
})
