/**
 * dsh-mobile-ui — live dev proxy.
 *
 *   node dev-proxy.mjs            (listens on http://127.0.0.1:3000)
 *
 * Why: the running DSH engine serves the real Web UI at 127.0.0.1:3080 with all
 * of its state (sessions, connection, plugins). This proxy forwards that exact
 * UI, but patches the boot manifest so the phone preview also mounts
 * dsh-mobile-ui — without touching the engine, the profile, or the session you
 * are chatting in. It never binds 3080/3083.
 *
 * What it does
 *   • proxies HTTP + WebSocket upgrades to 127.0.0.1:3080 (Host/Origin rewritten)
 *   • injects a small bootstrap script into index.html that
 *       – appends the plugin row to window.__DSH_BOOT__ (entry + initial batch),
 *       – captures page errors and boot progress,
 *       – polls /__ver and reloads when src/* changes (hot iteration),
 *   • serves the generated client bundle at /dev/mobile-client.js
 *   • stores diagnostics posted by the page under .diag/
 */
import http from 'node:http'
import net from 'node:net'
import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { assembleBundle } from './build.mjs'

const ROOT = new URL('./', import.meta.url)
const ROOT_PATH = fileURLToPath(ROOT)
const DIAG_DIR = new URL('.diag/', ROOT)
const LISTEN_PORT = Number(process.env.DSH_MOBILE_UI_PORT || 3000)
const UPSTREAM_HOST = '127.0.0.1'
const UPSTREAM_PORT = Number(process.env.DSH_WEB_PORT || 3080)
const PLUGIN_ID = 'dsh-mobile-ui'
const PLUGIN_ROUTE = '/dev/mobile-client.js'

mkdirSync(DIAG_DIR, { recursive: true })

/** Append one line to a diagnostics log with an ISO timestamp. */
function appendLog(name, line) {
  try {
    appendFileSync(new URL(name, DIAG_DIR), '[' + new Date().toISOString() + '] ' + line + '\n')
  } catch (error) {
    console.error('dev-proxy: cannot write diagnostics', error)
  }
}

/** Current bundle text + revision. */
function bundle() {
  return assembleBundle()
}

/** The bootstrap script injected into index.html. */
function bootstrapScript(rev) {
  return [
    '<script>',
    '/* [dsh-mobile-ui dev] preview-only bootstrap — not part of the shipped UI */',
    '(function () {',
    '  var REV = ' + JSON.stringify(rev) + ';',
    '  window.__DSH_MOBILE_UI_FORCE__ = true;',
    '  window.__DSH_MOBILE_DIAG__ = { rev: REV, ver: "/__ver" };',
    '  function send(path, data) {',
    '    try {',
    '      fetch(path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(data), keepalive: true }).catch(function () {});',
    '    } catch (error) {}',
    '  }',
    '  window.__DSH_MOBILE_DEV_SEND__ = send;',
    '  window.addEventListener("error", function (event) {',
    '    send("/__log", { kind: "error", message: String(event.message), source: String(event.filename || ""), line: event.lineno || 0, stack: event.error && event.error.stack ? String(event.error.stack) : "" });',
    '  });',
    '  window.addEventListener("unhandledrejection", function (event) {',
    '    var reason = event.reason;',
    '    send("/__log", { kind: "unhandledrejection", message: reason && reason.message ? String(reason.message) : String(reason), stack: reason && reason.stack ? String(reason.stack) : "" });',
    '  });',
    '  try {',
    '    var graph = window.__DSH_BOOT__;',
    '    var url = "dev/mobile-client.js?rev=" + REV;',
    '    /* Once the plugin is installed in the profile it is already part of the',
    '     * boot graph; injecting it again would register the same module id twice.',
    '     * The /__log note still reports which mode the preview is in. */',
    '    var installed = false;',
    '    for (var i = 0; i < graph.entries.length; i += 1) {',
    '      if (graph.entries[i] && graph.entries[i].id === ' + JSON.stringify(PLUGIN_ID) + ') { installed = true; break; }',
    '    }',
    '    if (installed) {',
    '      send("/__log", { kind: "graph-installed", entries: graph.entries.length, batches: graph.batches.length });',
    '    } else {',
    '      graph.entries.push({ id: ' + JSON.stringify(PLUGIN_ID) + ', url: url, rev: REV, inject: [], external: [], immediately: false });',
    '      graph.batches.push({ phase: "application", url: url, rev: REV, entries: [' + JSON.stringify(PLUGIN_ID) + '] });',
    '      send("/__log", { kind: "graph-patched", entries: graph.entries.length, batches: graph.batches.length });',
    '    }',
    '  } catch (error) {',
    '    send("/__log", { kind: "graph-patch-failed", message: String(error) });',
    '  }',
    '  var reported = {};',
    '  function snapshot(phase) {',
    '    if (reported[phase]) return;',
    '    reported[phase] = true;',
    '    var root = document.getElementById("root");',
    '    var boot = document.querySelector("[data-dsh-boot]");',
    '    send("/__log", {',
    '      kind: "snapshot",',
    '      phase: phase,',
    '      url: location.href,',
    '      mobile: document.documentElement.getAttribute("data-dsh-mobile"),',
    '      drawer: document.documentElement.getAttribute("data-dshm-drawer"),',
    '      info: document.documentElement.getAttribute("data-dshm-info"),',
    '      bar: !!document.getElementById("dshm-bar"),',
    '      rootChildren: root ? root.childElementCount : -1,',
    '      bootText: boot ? String(boot.innerText || "").slice(0, 300) : ""',
    '    });',
    '  }',
    '  setTimeout(function () { snapshot("t3"); }, 3000);',
    '  setTimeout(function () { snapshot("t8"); }, 8000);',
    '  var current = REV;',
    '  setInterval(function () {',
    '    fetch("/__ver?_=" + Date.now(), { cache: "no-store" }).then(function (response) { return response.text(); }).then(function (next) {',
    '      if (next && current && next !== current) window.location.reload();',
    '      else if (next) current = next;',
    '    }).catch(function () {});',
    '  }, 1500);',
    '})();',
    '</script>'
  ].join('\n')
}

/**
 * Rewrite one HMR graph frame so the preview keeps the plugin mounted.
 *
 * Why: after boot the engine pushes its authoritative plugin graph over the
 * /plugins/events SSE channel, and the browser reconciles its entry roster to it
 * — which would unmount a plugin that only exists in the HTML-injected graph
 * (observed: chrome appeared for ~1s, then disappeared). Adding the row to every
 * graph frame keeps the preview stable.
 */
function patchGraphFrame(frame, rev) {
  if (!frame || frame.type !== 'graph' || !frame.graph) return frame
  const graph = frame.graph
  if (!Array.isArray(graph.entries) || !Array.isArray(graph.batches)) return frame
  if (graph.entries.some((row) => row && row.id === PLUGIN_ID)) return frame
  const url = 'dev/mobile-client.js?rev=' + rev
  graph.entries = graph.entries.concat([
    { id: PLUGIN_ID, url: url, rev: rev, inject: [], external: [], immediately: false }
  ])
  graph.batches = graph.batches.concat([
    { phase: 'application', url: url, rev: rev, entries: [PLUGIN_ID] }
  ])
  return frame
}

/** Rewrite one complete SSE event block (possibly several data lines). */
function patchSseEvent(block, rev) {
  const lines = block.split('\n')
  let changed = false
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]
    if (line.indexOf('data:') !== 0) continue
    const payload = line.slice(5).trim()
    if (payload === '') continue
    try {
      const frame = patchGraphFrame(JSON.parse(payload), rev)
      lines[index] = 'data: ' + JSON.stringify(frame)
      changed = true
    } catch (error) {
      /* not ours: leave the wire bytes untouched */
    }
  }
  return changed ? lines.join('\n') : block
}

/** Stream the HMR event channel through the graph patcher. */
function pipeSse(upstreamResponse, response, rev) {
  let buffer = ''
  response.writeHead(upstreamResponse.statusCode || 200, {
    'content-type': 'text/event-stream',
    'cache-control': 'no-cache',
    connection: 'keep-alive'
  })
  upstreamResponse.setEncoding('utf8')
  upstreamResponse.on('data', (chunk) => {
    buffer += chunk
    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() || ''
    for (const block of blocks) response.write(patchSseEvent(block, rev) + '\n\n')
  })
  upstreamResponse.on('end', () => {
    if (buffer.length > 0) response.write(patchSseEvent(buffer, rev))
    response.end()
  })
  upstreamResponse.on('error', () => {
    try {
      response.destroy()
    } catch (ignored) {}
  })
  response.on('close', () => upstreamResponse.destroy())
}

/** Inject the dev bootstrap right before the first </head>. */
function injectIntoHtml(html, rev) {
  var marker = '<' + '/head>'
  var index = html.indexOf(marker)
  if (index === -1) return html
  return html.slice(0, index) + bootstrapScript(rev) + '\n' + html.slice(index)
}

/** Read a JSON request body (bounded). */
function readBody(request, limit, callback) {
  var chunks = []
  var size = 0
  var done = false
  request.on('data', function (chunk) {
    if (done) return
    size += chunk.length
    if (size > limit) {
      done = true
      callback(new Error('body too large'))
      return
    }
    chunks.push(chunk)
  })
  request.on('end', function () {
    if (done) return
    done = true
    callback(null, Buffer.concat(chunks).toString('utf8'))
  })
  request.on('error', function (error) {
    if (done) return
    done = true
    callback(error)
  })
}

/** Local endpoints that never reach the engine. */
function handleLocal(request, response) {
  var url = request.url || '/'

  if (url.indexOf('/__ver') === 0) {
    var rev = bundle().rev
    response.writeHead(200, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' })
    response.end(rev)
    return true
  }

  if (url.indexOf('/__diag') === 0) {
    readBody(request, 4 * 1024 * 1024, function (error, body) {
      if (error) {
        response.writeHead(400)
        response.end('bad diag body')
        return
      }
      try {
        const parsed = JSON.parse(body)
        writeFileSync(new URL('last.json', DIAG_DIR), JSON.stringify(parsed, null, 2))
        appendLog('diag.jsonl', body)
        console.log('dev-proxy: diag ' + String(parsed.reason || parsed.kind || '?') + ' (' + body.length + ' bytes)')
      } catch (parseError) {
        appendLog('log.txt', 'diag parse error: ' + String(parseError) + ' body=' + body.slice(0, 400))
      }
      response.writeHead(204, { 'cache-control': 'no-store' })
      response.end()
    })
    return true
  }

  if (url.indexOf('/__log') === 0) {
    readBody(request, 1024 * 1024, function (error, body) {
      if (error) {
        response.writeHead(400)
        response.end('bad log body')
        return
      }
      appendLog('log.txt', body)
      console.log('dev-proxy: page log ' + body.slice(0, 500))
      response.writeHead(204, { 'cache-control': 'no-store' })
      response.end()
    })
    return true
  }

  if (url.indexOf(PLUGIN_ROUTE) === 0) {
    var built = bundle()
    response.writeHead(200, {
      'content-type': 'text/javascript; charset=utf-8',
      'cache-control': 'no-store'
    })
    response.end(built.code)
    return true
  }

  return false
}

/** Proxy one HTTP request to the engine. */
function proxyHttp(request, response) {
  var target = request.url || '/'
  var headers = Object.assign({}, request.headers)
  headers.host = UPSTREAM_HOST + ':' + UPSTREAM_PORT
  if (headers.origin) headers.origin = 'http://' + UPSTREAM_HOST + ':' + UPSTREAM_PORT
  if (headers.referer) headers.referer = 'http://' + UPSTREAM_HOST + ':' + UPSTREAM_PORT + '/'
  delete headers['accept-encoding']
  delete headers['content-length']

  var upstream = http.request(
    { host: UPSTREAM_HOST, port: UPSTREAM_PORT, path: target, method: request.method, headers: headers },
    function (upstreamResponse) {
      var contentType = String(upstreamResponse.headers['content-type'] || '')
      if (String(request.url || '').indexOf('/plugins/events') !== -1) {
        pipeSse(upstreamResponse, response, bundle().rev)
        return
      }
      if (contentType.indexOf('text/html') !== -1 && request.method === 'GET') {
        var chunks = []
        upstreamResponse.on('data', function (chunk) {
          chunks.push(chunk)
        })
        upstreamResponse.on('end', function () {
          var html = Buffer.concat(chunks).toString('utf8')
          var rev = bundle().rev
          var patched = injectIntoHtml(html, rev)
          var outHeaders = Object.assign({}, upstreamResponse.headers)
          delete outHeaders['content-length']
          delete outHeaders['content-encoding']
          delete outHeaders['transfer-encoding']
          response.writeHead(upstreamResponse.statusCode || 200, outHeaders)
          response.end(patched)
        })
        upstreamResponse.on('error', function () {
          try {
            response.destroy()
          } catch (ignored) {}
        })
        return
      }
      response.writeHead(upstreamResponse.statusCode || 200, upstreamResponse.headers)
      upstreamResponse.pipe(response)
    }
  )

  upstream.on('error', function (error) {
    appendLog('log.txt', 'upstream error ' + target + ': ' + String(error))
    if (!response.headersSent) {
      response.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' })
    }
    response.end('dev-proxy upstream error: ' + String(error && error.message ? error.message : error))
  })

  request.on('aborted', function () {
    upstream.destroy()
  })
  request.pipe(upstream)
}

const server = http.createServer(function (request, response) {
  try {
    if (handleLocal(request, response)) return
  } catch (error) {
    appendLog('log.txt', 'local handler error: ' + String(error))
    response.writeHead(500)
    response.end('dev-proxy error')
    return
  }
  proxyHttp(request, response)
})

/* WebSocket / upgrade passthrough (the engine's /api/remote.mux etc.). */
server.on('upgrade', function (request, socket, head) {
  var headers = Object.assign({}, request.headers)
  headers.host = UPSTREAM_HOST + ':' + UPSTREAM_PORT
  if (headers.origin) headers.origin = 'http://' + UPSTREAM_HOST + ':' + UPSTREAM_PORT

  var upstream = net.connect(UPSTREAM_PORT, UPSTREAM_HOST, function () {
    var lines = [request.method + ' ' + (request.url || '/') + ' HTTP/1.1']
    Object.keys(headers).forEach(function (key) {
      var value = headers[key]
      if (Array.isArray(value)) value = value.join(', ')
      lines.push(key + ': ' + value)
    })
    upstream.write(lines.join('\r\n') + '\r\n\r\n')
    if (head && head.length > 0) upstream.write(head)
    socket.pipe(upstream)
    upstream.pipe(socket)
  })

  upstream.on('error', function (error) {
    appendLog('log.txt', 'ws upgrade error: ' + String(error))
    try {
      socket.destroy()
    } catch (ignored) {}
  })
  socket.on('error', function () {
    upstream.destroy()
  })
})

server.requestTimeout = 0
server.headersTimeout = 0
server.keepAliveTimeout = 120000

server.listen(LISTEN_PORT, '127.0.0.1', function () {
  const first = bundle()
  console.log('')
  console.log('dsh-mobile-ui dev proxy')
  console.log('  preview : http://127.0.0.1:' + LISTEN_PORT + '/')
  console.log('  forwards: http://' + UPSTREAM_HOST + ':' + UPSTREAM_PORT + ' (real engine UI)')
  console.log('  plugin  : ' + PLUGIN_ROUTE + ' rev=' + first.rev + ' bytes=' + Buffer.byteLength(first.code))
  console.log('  diag    : ' + new URL('last.json', DIAG_DIR).href)
  console.log('')
})
