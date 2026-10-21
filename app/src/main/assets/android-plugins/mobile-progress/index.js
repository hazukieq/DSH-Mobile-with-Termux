/**
 * dsh-mobile-progress（host 侧，v1.3.97）
 *
 * 用 DSH 官方插件 API 直接订阅 agent/assistant-stream 与 session/event，
 * 把**结构化的思考链路**（reasoning / tool-call / tool-result / answer）推给
 * Android 的 AgentBridge（POST /overlay，X-DSH-Token 鉴权），不再靠 WebUI DOM 抓取。
 *
 * 参考（开源仓库 docs/cookbook/extension-cookbook.md、docs/subsystems/core.md）：
 *   ctx.on('agent/assistant-stream', ({ agent, frame }) => …)
 *   frame = { type:'start' | 'chunk' | 'end' , chunk: StreamChunk }
 *   StreamChunk = block-start | text-delta | reasoning-delta | tool-call-delta | block-end | usage | finish
 *   ctx.on('session/event', (session, event) => …)  // tool/call、tool/result 等持久事实
 */

const name = 'mobile-progress'
const inject = ['agents', 'sessions']

const BRIDGE_URL = 'http://127.0.0.1:3083/overlay'
const THROTTLE_MS = 250
const MAX_CHARS = 4000

function token() {
  return process.env.DSH_BRIDGE_TOKEN || ''
}

function post(payload) {
  const t = token()
  if (!t) return
  fetch(BRIDGE_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'X-DSH-Token': t },
    body: JSON.stringify(payload),
  }).catch(() => {})
}

function clean(text) {
  return String(text || '').replace(/\s+/g, ' ').trim()
}

function clip(text, max) {
  const t = clean(text)
  return t.length > max ? t.slice(0, max) + '…' : t
}

function tailJoin(lines) {
  let out = lines.join('\n')
  if (out.length > MAX_CHARS) {
    out = out.slice(out.length - MAX_CHARS)
    const nl = out.indexOf('\n')
    if (nl >= 0) out = out.slice(nl + 1)
  }
  return out
}

export { name, inject }

export function apply(ctx) {
  /** agentId -> 本条尝试的状态 */
  const live = new Map()

  const stateOf = (agent) => {
    let s = live.get(agent.id)
    if (!s) {
      s = {
        id: agent.id,
        title: '',
        items: [],
        cur: null,
        started: Date.now(),
        timer: null,
        finished: false,
        turn: -1,
        status: 'idle',
        lastPost: 0,
      }
      live.set(agent.id, s)
    }
    return s
  }

  const pushItem = (s, t, extra) => {
    const it = { t, text: '', ...(extra || {}) }
    s.items.push(it)
    s.cur = it
    return it
  }

  const render = (s) => {
    const lines = []
    for (const it of s.items) {
      if (it.t === 'think') {
        const t = clean(it.text)
        if (t) lines.push('[思考] ' + clip(t, 700))
      } else if (it.t === 'tool') {
        const args = clean(it.args)
        const head = clean(it.name) || '工具调用'
        lines.push('[工具] ' + clip(head + (args ? ': ' + args : ''), 260))
        if (it.status) lines.push('[结果] ' + head + ' → ' + it.status)
      } else if (it.t === 'reply') {
        const t = clean(it.text)
        if (t) lines.push('[回复] ' + clip(t, 700))
      }
    }
    return tailJoin(lines)
  }

  const flush = (s, kind) => {
    const detail = render(s)
    if (!detail) return
    s.lastPost = Date.now()
    post({
      kind: kind || 'thinking',
      session: s.id,
      title: s.title || s.id.slice(-6),
      detail,
      elapsed: Date.now() - s.started,
    })
  }

  const schedule = (s) => {
    if (s.timer) return
    s.timer = setTimeout(() => {
      s.timer = null
      flush(s, 'thinking')
    }, THROTTLE_MS)
  }

  ctx.on('agent/assistant-stream', ({ agent, frame }) => {
    try {
      const s = stateOf(agent)
      if (!s.title) {
        try { s.title = agent.session?.title || '' } catch { /* noop */ }
      }
      if (frame.type === 'start') {
        // 同一 turn 的多个 step 会开新的 attempt：只在新 turn 开始时重置链路，
        // 否则多步任务里前面几步的思考/工具会被清掉（用户实测的「链路断片」）。
        if (frame.turn !== s.turn) {
          s.items = []
          s.turn = frame.turn
          s.started = Date.now()
        }
        s.cur = null
        s.finished = false
        return
      }
      if (frame.type === 'end') {
        if (s.timer) { clearTimeout(s.timer); s.timer = null }
        s.finished = true
        flush(s, 'done')
        return
      }
      if (frame.type === 'chunk') {
        const c = frame.chunk
        if (c.type === 'block-start') {
          if (c.blockType === 'reasoning') pushItem(s, 'think')
          else if (c.blockType === 'text') pushItem(s, 'reply')
          else if (c.blockType === 'tool-call') pushItem(s, 'tool', { callId: '', name: '', args: '' })
          else s.cur = null
        } else if (c.type === 'reasoning-delta') {
          if (!s.cur || s.cur.t !== 'think') pushItem(s, 'think')
          s.cur.text += c.text || ''
        } else if (c.type === 'text-delta') {
          if (!s.cur || s.cur.t !== 'reply') pushItem(s, 'reply')
          s.cur.text += c.text || ''
        } else if (c.type === 'tool-call-delta') {
          if (!s.cur || s.cur.t !== 'tool') pushItem(s, 'tool', { callId: c.id || '', name: '', args: '' })
          if (c.id) s.cur.callId = c.id
          if (c.name) s.cur.name = c.name
          s.cur.args += c.argumentsDelta || ''
        } else if (c.type === 'block-end') {
          s.cur = null
        }
        schedule(s)
      }
    } catch (e) {
      // 插件永远不能影响引擎主循环
    }
  })

  // Agent 状态：running→idle 是「会话真的结束了」的最可靠信号。
  // 之前只靠 assistant-stream 的 end 帧，遇到长工具/多 attempt 时可能漏掉，
  // 导致浮窗卡在 thinking（用户实测：23s 完成但状态没变、内容卡一半）。
  ctx.on('agent/status', ({ agent, status }) => {
    try {
      const s = stateOf(agent)
      s.status = status
      if (status === 'idle') {
        if (s.timer) { clearTimeout(s.timer); s.timer = null }
        flush(s, 'done')
      } else {
        schedule(s)
      }
    } catch (e) { /* ignore */ }
  })

  // 心跳：长时间工具调用期间没有 reasoning 增量时，也让浮窗有更新（避免看起来停滞）
  setInterval(() => {
    try {
      const now = Date.now()
      for (const s of live.values()) {
        if (s.status === 'running' && !s.timer && now - s.lastPost > 2500) flush(s, 'thinking')
      }
    } catch (e) { /* ignore */ }
  }, 1000).unref?.()

  // 持久事实：tool/call 补齐工具名与参数，tool/result 标记成功/失败
  ctx.on('session/event', (session, event) => {
    try {
      const agent = ctx.agents?.get?.(session.id)
      if (!agent) return
      const s = stateOf(agent)
      if (event.type === 'tool/call') {
        const callId = String(event.callId || '')
        let it = s.items.find((x) => x.t === 'tool' && x.callId === callId)
        if (!it) it = pushItem(s, 'tool', { callId, name: '', args: '' })
        it.name = event.name || it.name
        if (event.arguments) it.args = String(event.arguments)
        schedule(s)
      } else if (event.type === 'tool/result') {
        const callId = String(event.callId || '')
        const it = s.items.find((x) => x.t === 'tool' && x.callId === callId)
        if (it) {
          it.status = event.message?.isError ? '失败' : '完成'
          schedule(s)
        }
      } else if (event.type === 'turn/start') {
        s.started = Date.now()
        s.finished = false
      }
    } catch (e) {
      // ignore
    }
  })
}
