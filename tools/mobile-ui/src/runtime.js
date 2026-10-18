/**
 * dsh-mobile-ui — browser runtime.
 *
 * Android-app-style mobile layer over the shipped DSH Web UI. It is deliberately
 * DOM/CSS-only (no React): the host shell owns React, and a skin must never fight
 * its renderer for component ownership. Three jobs:
 *
 *  1. TOP APP BAR — a fixed 52dp bar with the sidebar button on the left and the
 *     top-info button on the right. It lives on <body>, outside the React root.
 *  2. POPUP SIDEBAR — the shipped left column is re-anchored as a fixed drawer
 *     (CSS) and expanded/collapsed through the shipped sidebar state machine, so
 *     the original navigation column is used as-is, just presented as an overlay.
 *  3. TOP-INFO PANEL — the shipped conversation header is hidden by default and
 *     revealed as a drop-down panel under the bar when the info button is tapped.
 *  4. COMPOSER — the shipped composer/transcript variables are re-bound to phone
 *     geometry (full-width card, 16px input text, 44dp targets, safe-area insets).
 *
 * Built by build.mjs into lib/client.js inside the dsh client-module factory
 * wrapper. `createMobileUi()` is called from that wrapper with the CSS text.
 */
function createMobileUi() {
  'use strict'

  /** Bump together with package.json version (shown in diagnostics). */
  var VERSION = '0.1.17'
  /** Fixed bar height in CSS px (safe-area inset added on top). */
  var BAR_HEIGHT = 52
  /** Phone breakpoint: above this the shipped desktop layout is left alone. */
  var MOBILE_MAX_WIDTH = 900
  /** How long the drawer is protected from reconcile right after a request. */
  var DRAWER_GRACE_MS = 900
  /** Modal surfaces worth reporting: the settings shell sets both. */
  var DIALOG_SELECTOR = '[role="dialog"], [aria-modal="true"]'
  /** Clicks whose label looks like chrome navigation are worth a diagnostics
   *  report: that is how a "tapping this does nothing" complaint gets evidence. */
  var REPORT_CLICK_PATTERN = /侧栏|侧边栏|面板|浏览器|终端|文件|设置|插件|信息|搜索|panel|sidebar|browser|terminal|file|settings|drawer/
  /** Right-pane memory keys (see setPaneOpen / rememberedPaneKind). */
  var PANE_OPEN_KEY = 'dshm.paneOpen'
  var PANE_KIND_KEY = 'dshm.paneKind'

  /** localStorage key holding the "skin is turned off" flag. */
  var DISABLED_KEY = 'dsh-mobile-ui:disabled'
  /** Element id of the revert row injected into the shipped settings page. */
  var REVERT_ROW_ID = 'dshm-revert-row'
  /** URL switch that bypasses (or restores) the skin without any UI at all. */
  var URL_SWITCH = 'dshm'


  var ICON_MENU =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linecap="round" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16"/></svg>'
  var ICON_PANEL =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linejoin="round" aria-hidden="true">' +
    '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M15 4v16"/></svg>'
  var ICON_INFO =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<circle cx="12" cy="12" r="9"/><path d="M12 11v5"/><path d="M12 8h.01"/></svg>'

  /**
   * Controls that live *inside* the drawer but are not navigation: search,
   * settings, the panel switcher, "new session", the brand row and the row
   * context menus. Tapping one of these must never close the popup — search and
   * settings sit in the same region as the session list, so a blanket
   * "any click inside the region" rule used to shut the drawer on the very tap
   * that was supposed to open them.
   */
  var DRAWER_CHROME_SELECTOR = [
    '.hHd-Xa_logoRow',
    '.hHd-Xa_topStrip',
    '.hHd-Xa_newSession',
    '.hHd-Xa_panelList',
    '.hHd-Xa_footerActions',
    '.hHd-Xa_settingsArea',
    '[class*="_searchSlot"]',
    '[class*="_searchButton"]',
    '[class*="_searchInput"]',
    '[class*="_clearButton"]',
    '[class*="_headerActions"]',
    '[class*="_rowActions"]',
    '[class*="_iconButton"]',
    '[class*="_viewOptionsMenu"]',
    'input',
    'textarea',
    'select',
    '[contenteditable="true"]',
    '[role="dialog"]',
    '[role="menu"]',
    '[role="listbox"]',
    '[data-dshm-bar]'
  ].join(',')

  /**
   * Rows that really navigate (pick a session / jump to a search hit).
   * Deliberately a positive list: if upstream renames these classes the drawer
   * simply stops auto-closing — a harmless failure — instead of swallowing every
   * tap inside the sidebar.
   */
  var DRAWER_NAV_SELECTOR = [
    '[class*="_sessionRow"]',
    '[class*="_searchResultRow"]',
    '[class*="_searchResultTitle"]'
  ].join(',')

  var state = {
    applied: false,
    available: false,
    drawer: false,
    info: false,
    bar: null,
    scrim: null,
    infoScrim: null,
    title: null,
    infoButton: null,
    menuButton: null,
    timer: null,
    ctx: null,
    css: '',
    diag: null,
    diagPhase: {},
    titleText: '',
    /** Last click seen inside the drawer, and why it did/did not close it. */
    lastClick: null,
    /** Which path closed the drawer most recently (scrim / nav-row / reconcile). */
    lastCloseReason: null,
    /** Recent click labels (ring buffer) reported with every snapshot. */
    clickTrace: [],
    clickTracer: false,
    panelButton: null,
    /** This layer's own right-pane presentation flag (see mobile.css). */
    paneOpen: false,
    /** Disposer returned to cordis, kept so the revert button can tear down. */
    dispose: null,
    /** Last seen frame attribute set / dialog set, for change-triggered reports. */
    frameSig: '',
    dialogSig: '',
    /* Wall-clock deadline until which reconcileDrawer() must not fight a
     * drawer request. The shipped layout service applies `data-sidebar-collapsed`
     * through React, so the DOM attribute can lag one or two frames behind our
     * click; without this grace window the 600ms sync tick reads the stale
     * "collapsed" state and slams the drawer shut right after it opened. */
    drawerGraceUntil: 0
  }

  /* ─────────────────────────── escape hatches ─────────────────────────── */

  /** localStorage, or null when the origin forbids it. */
  function storage() {
    try {
      return window.localStorage || null
    } catch (error) {
      return null
    }
  }

  /** Whether this browser has rolled the skin back. */
  function skinDisabled() {
    var store = storage()
    if (store === null) return false
    try {
      return store.getItem(DISABLED_KEY) === '1'
    } catch (error) {
      return false
    }
  }

  /** Persist (or clear) the rollback flag for this browser. */
  function setSkinDisabled(value) {
    var store = storage()
    if (store === null) return
    try {
      if (value) store.setItem(DISABLED_KEY, '1')
      else store.removeItem(DISABLED_KEY)
    } catch (error) {
      /* blocked storage: the URL switch still works */
    }
  }

  /**
   * Handle `?dshm=off` / `?dshm=on`.
   * Last-resort escape: it needs no UI at all, so it still works when the skin
   * has broken the page. The parameter is stripped and the page reloaded, so the
   * next load cleanly runs (or skips) the skin.
   */
  function handleUrlSwitch() {
    var url = null
    try {
      url = new URL(window.location.href)
    } catch (error) {
      return false
    }
    var flag = null
    try {
      flag = url.searchParams.get(URL_SWITCH)
    } catch (error) {
      return false
    }
    if (flag === null) return false
    var off = flag === 'off' || flag === '0'
    var on = flag === 'on' || flag === '1'
    if (!off && !on) return false
    url.searchParams.delete(URL_SWITCH)
    setSkinDisabled(off)
    hostSwitch(off ? 'rollback' : 'restore')
    try {
      window.location.replace(url.href)
    } catch (error) {
      /* the flag already applies to the next load */
    }
    return true
  }

  /**
   * Ask the host whether the skin is switched off.
   * The host flag is global to the engine, which is the only way a rollback can
   * reach the App's WebView (no address bar, so no URL switch there).
   * @param done - called exactly once with the host's verdict
   */
  function consultHostState(done) {
    var settled = false
    var finish = function (disabled) {
      if (settled) return
      settled = true
      done(disabled === true)
    }
    try {
      if (typeof fetch !== 'function') {
        finish(false)
        return
      }
      var timer = window.setTimeout(function () {
        finish(false)
      }, 1200)
      fetch('/__mobile-ui/state', { cache: 'no-store' })
        .then(function (response) {
          return response.ok ? response.json() : null
        })
        .then(function (body) {
          window.clearTimeout(timer)
          finish(body !== null && body !== undefined && body.disabled === true)
        })
        .catch(function () {
          window.clearTimeout(timer)
          finish(false)
        })
    } catch (error) {
      finish(false)
    }
  }

  /** Fire-and-forget call to one of the host rollback routes. */
  function hostSwitch(action) {
    try {
      if (typeof fetch === 'function') {
        fetch('/__mobile-ui/' + action, { cache: 'no-store' }).catch(function () {})
      }
    } catch (error) {
      /* the host flag is best effort; the local flag still applies */
    }
  }

  /** Roll back from inside the page: flags, teardown, reload. */
  function rollback(reason) {
    setSkinDisabled(true)
    hostSwitch('rollback')
    var finished = false
    var finish = function () {
      if (finished) return
      finished = true
      try {
        if (state.dispose !== null) state.dispose()
      } catch (error) {
        /* tearing down a broken DOM may throw; the flags already protect us */
      }
      try {
        reportDiag('rollback:' + String(reason || 'manual'))
      } catch (error) {
        /* diagnostics are best effort */
      }
      try {
        window.location.reload()
      } catch (error) {
        /* the flags still apply on the next manual reload */
      }
    }
    /* Give the host call a moment to land, but never block the rollback on it. */
    try {
      fetch('/__mobile-ui/rollback', { cache: 'no-store' }).then(finish).catch(finish)
    } catch (error) {
      finish()
    }
    window.setTimeout(finish, 500)
  }

  /** The revert row shown inside the shipped settings page. */
  function buildRevertRow() {
    var row = document.createElement('div')
    row.id = REVERT_ROW_ID
    var title = document.createElement('div')
    title.className = 'dshm-revert-title'
    title.textContent = '移动端界面（dsh-mobile-ui v' + VERSION + '）'
    var desc = document.createElement('div')
    desc.className = 'dshm-revert-desc'
    desc.textContent = '当前已启用。回退会立即恢复原版界面，并在本浏览器记住这个选择。'
    var button = document.createElement('button')
    button.type = 'button'
    button.className = 'dshm-revert-btn'
    button.textContent = '回退到原版界面'
    button.setAttribute('aria-label', '回退到原版界面')
    button.addEventListener('click', function (event) {
      event.preventDefault()
      event.stopPropagation()
      rollback('settings-button')
    })
    var hint = document.createElement('div')
    hint.className = 'dshm-revert-desc'
    hint.textContent = '如果界面已经异常、点不到这里：地址后加 ?dshm=off 立即回退，加 ?dshm=on 恢复。'
    row.appendChild(title)
    row.appendChild(desc)
    row.appendChild(button)
    row.appendChild(hint)
    return row
  }

  /** Keep the revert row present while the settings page is open. */
  function syncRevertRow() {
    var panel = q('.VOzbGW_panel')
    var host = panel === null ? null : panel.querySelector('.VOzbGW_options')
    var existing = document.getElementById(REVERT_ROW_ID)
    if (host === null) {
      if (existing !== null && existing.parentNode !== null) existing.parentNode.removeChild(existing)
      return
    }
    if (existing !== null && existing.parentNode === host) return
    if (existing !== null && existing.parentNode !== null) existing.parentNode.removeChild(existing)
    host.insertBefore(buildRevertRow(), host.firstChild)
  }

  /* ────────────────────────────── helpers ────────────────────────────── */

  /** True when this page should get the phone chrome. */
  function isMobile() {
    if (window.__DSH_MOBILE_UI_FORCE__ === true) return true
    var w = window.innerWidth || document.documentElement.clientWidth || 0
    if (w > 0 && w <= MOBILE_MAX_WIDTH) return true
    var ua = navigator.userAgent || ''
    if (/Android|iPhone|iPod|Mobile/i.test(ua)) return true
    return false
  }

  /** Find one element, tolerating the shipped hashed CSS module class names. */
  function q(selector) {
    try {
      return document.querySelector(selector)
    } catch (error) {
      return null
    }
  }

  /** Find every element for a selector (never throws on legacy WebViews). */
  function qa(selector) {
    try {
      return Array.prototype.slice.call(document.querySelectorAll(selector))
    } catch (error) {
      return []
    }
  }

  /** Current frame element (the shipped three-column grid). */
  function frame() {
    return q('.pI_x6G_frame')
  }

  /** Current shipped conversation header (top-info content), if rendered. */
  function conversationHeader() {
    return q('.wSkVaW_header')
  }

  /**
   * The sidebar expand/collapse control inside the left column.
   * Covers the shipped sidebar (`..._toggle`) and third-party skins such as
   * dsh-codex-ui, whose compact rail exposes a labelled icon button instead.
   */
  function sidebarToggleButton() {
    var col = q('.pI_x6G_sidebarCol')
    if (col === null) return null
    var buttons = Array.prototype.slice.call(col.querySelectorAll('button'))
    var i
    for (i = 0; i < buttons.length; i += 1) {
      var cls = String(buttons[i].className || '')
      if (cls.indexOf('_toggle') !== -1) return buttons[i]
    }
    var labelPattern = /侧栏|侧边栏|展开|收起|sidebar|expand|collapse|navigation/i
    for (i = 0; i < buttons.length; i += 1) {
      var label = String(buttons[i].getAttribute('aria-label') || buttons[i].getAttribute('title') || '')
      if (labelPattern.test(label)) return buttons[i]
    }
    return null
  }


  /** Whether the shipped frame currently reports the sidebar collapsed. */
  function sidebarCollapsed() {
    var el = frame()
    return el !== null && el.hasAttribute('data-sidebar-collapsed')
  }

  /** Toggle the shipped sidebar state machine (layout service first, DOM fallback). */
  function toggleSidebar() {
    var ctx = state.ctx
    try {
      var layout = ctx && typeof ctx.get === 'function' ? ctx.get('layout') : void 0
      if (layout && typeof layout.toggleSidebar === 'function') {
        layout.toggleSidebar()
        return
      }
    } catch (error) {
      /* fall through to the DOM control */
    }
    var button = sidebarToggleButton()
    if (button !== null) button.click()
  }

  /** Inject (or refresh) the plugin stylesheet. */
  function injectCss(css) {
    var id = 'dsh-mobile-ui/style'
    var existing = document.getElementById(id)
    if (existing !== null) {
      if (existing.textContent !== css) existing.textContent = css
      return
    }
    var tag = document.createElement('style')
    tag.id = id
    tag.setAttribute('data-plugin', 'dsh-mobile-ui')
    tag.setAttribute('data-plugin-css', '@dsh-mobile-ui/mobile.css')
    tag.textContent = css
    document.head.appendChild(tag)
  }

  /** Build a button with an inline SVG icon. */
  function makeButton(id, label, icon) {
    var button = document.createElement('button')
    button.type = 'button'
    button.id = id
    button.className = 'dshm-icon-btn'
    button.setAttribute('aria-label', label)
    button.setAttribute('title', label)
    button.innerHTML = icon
    return button
  }

  /** Mount the persistent chrome (bar + scrims). Idempotent. */
  function mountChrome() {
    if (state.bar !== null && document.body.contains(state.bar)) return
    if (document.body === null) return

    var bar = document.createElement('div')
    bar.id = 'dshm-bar'
    bar.className = 'dshm-bar'
    bar.setAttribute('role', 'banner')

    var menu = makeButton('dshm-menu', '菜单', ICON_MENU)
    var panel = makeButton('dshm-panel', '侧栏面板', ICON_PANEL)
    var info = makeButton('dshm-info', '会话信息', ICON_INFO)
    var title = document.createElement('div')
    title.id = 'dshm-title'
    title.className = 'dshm-title'
    title.setAttribute('role', 'heading')
    title.setAttribute('aria-level', '1')
    title.textContent = ''

    var scrim = document.createElement('div')
    scrim.id = 'dshm-scrim'
    scrim.className = 'dshm-scrim'
    scrim.setAttribute('aria-hidden', 'true')

    var infoScrim = document.createElement('div')
    infoScrim.id = 'dshm-info-scrim'
    infoScrim.className = 'dshm-info-scrim'
    infoScrim.setAttribute('aria-hidden', 'true')

    bar.appendChild(menu)
    bar.appendChild(title)
    bar.appendChild(panel)
    bar.appendChild(info)
    document.body.appendChild(bar)
    document.body.appendChild(scrim)
    document.body.appendChild(infoScrim)

    menu.addEventListener('click', function (event) {
      event.preventDefault()
      event.stopPropagation()
      toggleDrawer()
    })
    panel.addEventListener('click', function (event) {
      event.preventDefault()
      event.stopPropagation()
      toggleRightPane()
    })
    info.addEventListener('click', function (event) {
      event.preventDefault()
      event.stopPropagation()
      setInfo(!state.info)
      reportDiag('info-toggle')
    })
    scrim.addEventListener('click', function () {
      closeDrawer('scrim')
    })
    infoScrim.addEventListener('click', function () {
      setInfo(false)
    })

    document.addEventListener(
      'click',
      function (event) {
        if (!state.info) return
        var target = event.target
        if (target === null || target.nodeType !== 1) return
        if (target.closest && target.closest('#dshm-bar')) return
        if (target.closest && target.closest('.wSkVaW_header')) return
        setInfo(false)
      },
      true
    )

    /* Close the popup only when a session / search-result row is actually picked.
     * Everything else in the region (search box, settings, row menus, inputs)
     * keeps the drawer open so the control the user just tapped stays usable. */
    document.addEventListener(
      'click',
      function (event) {
        if (!state.drawer) return
        var target = event.target
        if (target === null || target.nodeType !== 1) return
        if (!target.closest) return
        state.lastClick = describeTarget(target)
        if (!target.closest('.hHd-Xa_regionArea')) return
        if (target.closest(DRAWER_CHROME_SELECTOR)) {
          state.lastClick.skip = 'chrome'
          return
        }
        if (!target.closest(DRAWER_NAV_SELECTOR)) {
          state.lastClick.skip = 'not-a-row'
          return
        }
        setTimeout(function () {
          closeDrawer('nav-row')
        }, 120)
      },
      true
    )

    if (!state.clickTracer) {
      state.clickTracer = true
      document.addEventListener(
        'click',
        function (event) {
          var target = event.target
          if (target === null || target.nodeType !== 1) return
          var info = describeTarget(target)
          state.clickTrace.push(info)
          if (state.clickTrace.length > 8) state.clickTrace.shift()
          var label = info.label || info.text || ''
          /* The pane carries its own collapse control; mirror it into our flag so
           * the overlay does not stay up as an empty shell. */
          if (state.paneOpen && target.closest && target.closest('.pI_x6G_rightbarCol') !== null) {
            var closer = target.closest(
              '[class*="collapse"], [class*="_close"], [aria-label*="关闭"], [aria-label*="收起"], [title*="关闭"]'
            )
            if (closer !== null) {
              setPaneOpen(false)
              var layout = layoutService()
              if (layout && typeof layout.closeRightbar === 'function') {
                try {
                  layout.closeRightbar()
                } catch (error) {
                  /* ignore */
                }
              }
            }
          }
          if (REPORT_CLICK_PATTERN.test(label)) reportDiag('click:' + label.slice(0, 16))
        },
        true
      )
    }

    state.bar = bar
    state.scrim = scrim
    state.infoScrim = infoScrim
    state.title = title
    state.infoButton = info
    state.menuButton = menu
    state.panelButton = panel

    updateTitle()
    updateInfoAvailability()
    updatePanelAvailability()
  }

  /* ────────────────────────────── right pane ────────────────────────────── */

  /**
   * Resolve one of the shipped services through every accessor this build has:
   * `ctx.get(name)` (used inside the shipped client bundles), `ctx.reflect.get`
   * (the provider side), then the plain property. A skin must not declare these
   * in `inject` — a missing service would then prevent the whole layer from
   * applying — so resolution has to be tolerant.
   */
  function service(name) {
    var ctx = state.ctx
    if (!ctx) return void 0
    try {
      if (typeof ctx.get === 'function') {
        var viaGet = ctx.get(name)
        if (viaGet !== void 0 && viaGet !== null) return viaGet
      }
    } catch (error) {
      /* try the next accessor */
    }
    try {
      if (ctx.reflect && typeof ctx.reflect.get === 'function') {
        var viaReflect = ctx.reflect.get(name)
        if (viaReflect !== void 0 && viaReflect !== null) return viaReflect
      }
    } catch (error) {
      /* try the next accessor */
    }
    try {
      var direct = ctx[name]
      if (direct !== void 0 && direct !== null) return direct
    } catch (error) {
      /* nothing left */
    }
    return void 0
  }

  /** The shipped layout service (right pane open/close). */
  function layoutService() {
    return service('layout')
  }

  /** The shipped right-pane tab service. */
  function rightPaneService() {
    return service('sidebarRight')
  }

  /** Tab kinds the right pane can host, in registration order. */
  function rightPaneKinds() {
    var kinds = []
    try {
      var ctx = state.ctx
      var registry = service('sidebarRightTabs')
      var list = []
      if (registry && typeof registry.keys === 'function') {
        list = Array.from(registry.keys())
      } else if (registry && typeof registry.entries === 'function') {
        list = Array.from(registry.entries())
      }
      /* Entries may be plain kinds, [kind, def] pairs, or tab definitions. */
      kinds = list
        .map(function (entry) {
          if (typeof entry === 'string') return entry
          if (Array.isArray(entry)) return entry[0]
          if (entry && typeof entry.kind === 'string') return entry.kind
          return void 0
        })
        .filter(function (kind) {
          return typeof kind === 'string' && kind.length > 0
        })
    } catch (error) {
      kinds = []
    }
    return kinds
  }

  /** Best available tab kind to open. */
  function preferredPaneKind() {
    var kinds = rightPaneKinds()
    var order = ['files', 'terminal', 'browser', 'deliverables', 'docs']
    for (var i = 0; i < order.length; i += 1) {
      if (kinds.indexOf(order[i]) !== -1) return order[i]
    }
    return kinds.length > 0 ? String(kinds[0]) : 'files'
  }

  /**
   * Whether the shipped right pane already owns tabs.
   *
   * The engine keeps its dock state (open tabs + active tab) across close/open
   * and reloads. Calling `openTab(kind)` on every open therefore forces that
   * kind to the front - which is why the pane kept jumping to the "guide" tab
   * even when a terminal tab was already there. Only place a tab when the pane
   * has none.
   */
  function rightPaneHasTabs() {
    var col = q('.pI_x6G_rightbarCol')
    if (col === null) return false
    return col.querySelectorAll('[role="tablist"] [role="tab"]').length > 0
  }

  /**
   * Whether this layer is presenting the right pane.
   * Deliberately not `data-rightbar-collapsed`: the shipped column math zeroes
   * the track on narrow viewports, so that attribute is permanently true here.
   */
  function rightPaneOpen() {
    return state.paneOpen === true
  }

  /** Publish the right-pane presentation to CSS (and remember it across loads). */
  function setPaneOpen(open) {
    state.paneOpen = !!open
    document.documentElement.setAttribute('data-dshm-pane', state.paneOpen ? 'open' : 'closed')
    try {
      var store = storage()
      if (store !== null) store.setItem(PANE_OPEN_KEY, state.paneOpen ? '1' : '0')
    } catch (error) {
      /* a private-mode origin can refuse storage; memory still works this load */
    }
  }

  /** Remember which right-pane tab the user opened last. */
  function rememberPaneKind(kind) {
    if (typeof kind !== 'string' || kind.length === 0) return
    try {
      var store = storage()
      if (store !== null) store.setItem(PANE_KIND_KEY, kind)
    } catch (error) {
      /* ignore */
    }
  }

  /** Last remembered right-pane tab kind, if the engine still registers it. */
  function rememberedPaneKind() {
    try {
      var store = storage()
      if (store === null) return ''
      var kind = store.getItem(PANE_KIND_KEY) || ''
      return typeof kind === 'string' ? kind : ''
    } catch (error) {
      return ''
    }
  }

  /** Whether the previous page load left the right pane open. */
  function storedPaneOpen() {
    try {
      var store = storage()
      return store !== null && store.getItem(PANE_OPEN_KEY) === '1'
    } catch (error) {
      return false
    }
  }

  /**
   * Open (placing a tab) or close the shipped right pane.
   * The shipped UI only opens this pane contextually, from inside another pane's
   * action, so a phone user has no reachable entry — this is that entry.
   */
  function toggleRightPane() {
    var layout = layoutService()
    if (rightPaneOpen()) {
      setPaneOpen(false)
      if (layout && typeof layout.closeRightbar === 'function') {
        try {
          layout.closeRightbar()
        } catch (error) {
          /* presentation is already closed on our side */
        }
      }
      window.setTimeout(function () {
        reportDiag('rightbar-toggle')
      }, 250)
      return
    }
    var kinds = rightPaneKinds()
    var remembered = rememberedPaneKind()
    var preferred = ['guide', 'files', 'terminal', 'plan']
    if (remembered !== '' && preferred.indexOf(remembered) === -1) preferred.unshift(remembered)
    var candidates = preferred
      .filter(function (kind) {
        return kinds.indexOf(kind) !== -1
      })
      .concat(
        kinds.filter(function (kind) {
          return preferred.indexOf(kind) === -1
        })
      )
    if (candidates.length === 0) candidates = ['guide', 'files', 'terminal']

    var kind = candidates[0]
    setPaneOpen(true)
    /* Report the track to the shipped layout (fullscreen: the phone presents the
     * pane as an overlay, never as a squeezed desktop column). */
    if (layout && typeof layout.openRightbar === 'function') {
      try {
        layout.openRightbar(kind, true)
      } catch (error) {
        /* presentation still works through our own attribute */
      }
    }
    /* Existing tabs belong to the engine's dock state: keep the user's active
     * tab instead of jumping to the first registered kind. */
    if (rightPaneHasTabs()) {
      reportDiag('rightbar-toggle')
      return
    }
    /* Placing a tab needs the session surface to be mounted, which happens one
     * React render after openRightbar(); retry the same kind a few times before
     * giving up on it and moving to the next registered kind. */
    var index = 0
    var attempts = 0
    var place = function () {
      if (index >= candidates.length) {
        reportDiag('rightbar-toggle')
        return
      }
      var next = candidates[index]
      var right = rightPaneService()
      var placed = false
      if (right && typeof right.openTab === 'function') {
        try {
          right.openTab(next, {})
          placed = true
        } catch (error) {
          placed = false
        }
      }
      if (placed) {
        rememberPaneKind(next)
        reportDiag('rightbar-toggle')
        return
      }
      attempts += 1
      if (attempts >= 3) {
        attempts = 0
        index += 1
      }
      window.setTimeout(place, 260)
    }
    window.setTimeout(place, 140)
  }

  /** What the right-pane wiring resolved to, for diagnostics. */
  function paneApiReport() {
    var layout = layoutService()
    var right = rightPaneService()
    return {
      ctxGet: !!(state.ctx && typeof state.ctx.get === 'function'),
      layout: layout === void 0 ? null : typeof layout.openRightbar === 'function',
      sidebarRight: right === void 0 ? null : typeof right.openTab === 'function',
      kinds: rightPaneKinds().slice(0, 8),
      open: rightPaneOpen(),
      modal: document.documentElement.getAttribute('data-dshm-modal')
    }
  }

  /** Hide the pane button when this build has no right pane at all. */
  function updatePanelAvailability() {
    if (state.panelButton === null) return
    var hasPane = layoutService() !== void 0 || rightPaneService() !== void 0
    if (hasPane !== !state.panelButton.hidden) state.panelButton.hidden = !hasPane
  }

  /* ────────────────────────────── drawer / info ────────────────────────────── */

  /** Open or close the popup sidebar drawer. */
  function setDrawer(open) {
    if (!state.available) return
    var frameEl = frame()
    var wanted = !!open
    var asked = false
    if (wanted && sidebarCollapsed()) {
      toggleSidebar()
      asked = true
    }
    if (!wanted && frameEl !== null && !sidebarCollapsed()) {
      toggleSidebar()
      asked = true
    }
    if (asked) state.drawerGraceUntil = Date.now() + DRAWER_GRACE_MS
    state.drawer = wanted
    document.documentElement.setAttribute('data-dshm-drawer', state.drawer ? 'open' : 'closed')
    if (state.menuButton !== null) state.menuButton.setAttribute('aria-expanded', state.drawer ? 'true' : 'false')
  }

  /** Toggle the drawer, then report the settled geometry. */
  function toggleDrawer() {
    setDrawer(!state.drawer)
    setTimeout(function () {
      reportDiag('drawer-toggle')
    }, 420)
  }

  /** Compact description of a click target, for diagnostics. */
  function describeTarget(el) {
    var label =
      (el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('placeholder'))) || ''
    var text = ''
    try {
      text = String(el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 24)
    } catch (error) {
      text = ''
    }
    return {
      tag: el.tagName ? el.tagName.toLowerCase() : '',
      cls: String(el.className || '').slice(0, 70),
      label: String(label).slice(0, 40),
      text: text
    }
  }

  /** Close the drawer if open, remembering why (visible in diagnostics). */
  function closeDrawer(reason) {
    if (!state.drawer) return
    state.lastCloseReason = reason || 'manual'
    setDrawer(false)
    window.setTimeout(function () {
      reportDiag('drawer-close')
    }, 420)
  }

  /** Show or hide the collapsed top-info panel. */
  function setInfo(open) {
    state.info = !!open
    document.documentElement.setAttribute('data-dshm-info', state.info ? 'open' : 'closed')
    if (state.infoButton !== null) state.infoButton.setAttribute('aria-expanded', state.info ? 'true' : 'false')
  }

  /* ────────────────────────────── chrome state sync ────────────────────────────── */

  /** Update the bar title from the shipped document title / session crumbs. */
  function updateTitle() {
    var text = ''
    var crumbs = q('.wSkVaW_crumbCurrent')
    if (crumbs !== null && crumbs.textContent) text = crumbs.textContent.trim()
    if (text === '') {
      text = String(document.title || '')
      text = text.replace(/\s+[\u2014-]\s+DeepSeek Harness\s*$/, '')
      if (text === 'DeepSeek Harness') text = ''
    }
    if (text === '') text = 'DeepSeek Harness'
    if (text !== state.titleText) {
      state.titleText = text
      if (state.title !== null) state.title.textContent = text
    }
  }

  /** Show the info button only where there is a conversation header to reveal. */
  function updateInfoAvailability() {
    if (state.infoButton === null) return
    var hasHeader = conversationHeader() !== null
    if (hasHeader !== !state.infoButton.hidden) {
      state.infoButton.hidden = !hasHeader
    }
    if (!hasHeader && state.info) setInfo(false)
  }

  /**
   * Keep the drawer consistent with the shipped sidebar state.
   * A collapse that happened inside the drawer (the sidebar's own collapse
   * control) means "close the drawer", not "re-open it" — fighting that would
   * trap the user inside a loop.
   */
  function reconcileDrawer() {
    if (!state.drawer) return
    if (Date.now() < state.drawerGraceUntil) return
    var frameEl = frame()
    if (frameEl === null) return
    if (sidebarCollapsed()) closeDrawer('reconcile')
  }

  /** Attributes on the shipped frame whose change means "the layout moved". */
  function frameAttrSignature() {
    var el = frame()
    if (el === null) return ''
    var names = ['data-sidebar-collapsed', 'data-rightbar-collapsed', 'data-rightbar-fullscreen']
    var out = []
    for (var i = 0; i < names.length; i += 1) {
      if (el.hasAttribute(names[i])) out.push(names[i])
    }
    return out.join(',')
  }

  /** Report whenever the right pane / sidebar postures change. */
  function checkFrameAttrs() {
    var sig = frameAttrSignature()
    if (sig === state.frameSig) return
    state.frameSig = sig
    reportDiag('frame-attrs')
  }

  /**
   * Signature of the open modal surfaces (class + size).
   *
   * Only dialogs that really own the screen count as "modal": the shipped UI
   * also marks small popovers (the subagent member list, pickers) as
   * role="dialog", and treating those as modal used to hide the app bar and
   * leave an empty band above the popup. A full-screen surface (settings) still
   * matches, so its bar-hiding rule keeps working.
   */
  function dialogSignature() {
    var list = qa(DIALOG_SELECTOR)
    var parts = []
    var vw = window.innerWidth || 0
    var vh = window.innerHeight || 0
    for (var i = 0; i < list.length && i < 3; i += 1) {
      var rect = rectOf(list[i])
      if (rect === null || rect[2] === 0 || rect[3] === 0) continue
      var big = rect[2] >= vw * 0.9 && rect[3] >= vh * 0.5
      if (!big) continue
      parts.push(String(list[i].className || '').slice(0, 24) + ':' + rect[2] + 'x' + rect[3])
    }
    return parts.join('|')
  }

  /** Report whenever a modal opens or closes (settings, pickers, confirmations). */
  function checkDialogs() {
    var sig = dialogSignature()
    if (sig === state.dialogSig) return
    state.dialogSig = sig
    document.documentElement.setAttribute('data-dshm-modal', sig === '' ? 'closed' : 'open')
    reportDiag('dialog')
  }

  /** Every visible floating surface (menus, popovers, dropdowns) with geometry. */
  function captureFloats() {
    var out = []
    var list = qa('[role="menu"], [role="listbox"], [data-trigger-menu], [class*="_menu"], [class*="_popover"], [class*="_dropdown"]')
    for (var i = 0; i < list.length && out.length < 6; i += 1) {
      var el = list[i]
      var rect = rectOf(el)
      if (rect === null || rect[2] === 0 || rect[3] === 0) continue
      var z = ''
      var inPanel = false
      try {
        z = getComputedStyle(el).zIndex
      } catch (error) {
        z = ''
      }
      try {
        inPanel = el.closest('.wSkVaW_header') !== null
      } catch (error) {
        inPanel = false
      }
      out.push({
        cls: String(el.className || '').slice(0, 50),
        rect: rect,
        z: z,
        inPanel: inPanel,
        offscreenBottom: rect[1] + rect[3] > (window.innerHeight || 0)
      })
    }
    return out
  }

  /**
   * Info panel + the floating layers it may have opened.
   * The top panel is a fixed grid that the shipped header renders into; its
   * action menus can be portaled into the overlay layer or nested inside the
   * panel, and only the measured z-index/containment tells them apart.
   */
  function captureInfoPanel() {
    var header = conversationHeader()
    if (header === null) return null
    var style = null
    try {
      var cs = getComputedStyle(header)
      style = {
        background: cs.backgroundColor,
        position: cs.position,
        zIndex: cs.zIndex,
        overflowY: cs.overflowY,
        maxHeight: cs.maxHeight
      }
    } catch (error) {
      style = null
    }
    var floats = captureFloats()
    var layer = q('.pI_x6G_overlayLayer')
    var overlayZ = ''
    try {
      overlayZ = layer === null ? '' : getComputedStyle(layer).zIndex
    } catch (error) {
      overlayZ = ''
    }
    return {
      rect: rectOf(header),
      style: style,
      overlayZ: overlayZ,
      floats: floats,
      outline: outline(header, 0, { left: 120 })
    }
  }

  /** Compact description of every open modal surface. */
  function captureDialogs() {
    var list = qa(DIALOG_SELECTOR)
    var out = []
    for (var i = 0; i < list.length && i < 3; i += 1) {
      var el = list[i]
      var style = null
      try {
        var cs = getComputedStyle(el)
        style = {
          position: cs.position,
          width: cs.width,
          maxWidth: cs.maxWidth,
          height: cs.height,
          maxHeight: cs.maxHeight,
          modal: el.getAttribute('aria-modal')
        }
      } catch (error) {
        style = null
      }
      out.push({
        cls: String(el.className || '').slice(0, 90),
        rect: rectOf(el),
        style: style,
        outline: outline(el, 0, { left: 320 }, 9)
      })
    }
    return out
  }

  /** Periodic / mutation-driven sync tick. */
  function sync() {
    if (!state.available) return
    if (document.documentElement.getAttribute('data-dsh-mobile') === null) {
      document.documentElement.setAttribute('data-dsh-mobile', '')
      document.documentElement.setAttribute('data-dshm-drawer', state.drawer ? 'open' : 'closed')
      document.documentElement.setAttribute('data-dshm-info', state.info ? 'open' : 'closed')
    }
    if (document.body.contains(state.bar) === false) {
      state.bar = null
      mountChrome()
    }
    updateTitle()
    updateInfoAvailability()
    updatePanelAvailability()
    reconcileDrawer()
    checkFrameAttrs()
    checkDialogs()
    syncRevertRow()
  }

  /* ────────────────────────────── diagnostics (dev only) ────────────────────────────── */

  /** Round a number for compact diagnostics. */
  function num(value) {
    return Math.round(value * 10) / 10
  }

  /** Compact rect of an element. */
  function rectOf(el) {
    if (!el) return null
    var r = el.getBoundingClientRect()
    return [num(r.left), num(r.top), num(r.width), num(r.height)]
  }

  /** Serialize one element tree (bounded) for layout diagnostics. */
  function outline(el, depth, budget, maxDepth) {
    var limit = maxDepth === void 0 ? 6 : maxDepth
    if (!el || depth > limit || budget.left <= 0) return null
    budget.left -= 1
    var item = {
      t: el.tagName ? el.tagName.toLowerCase() : '',
      c: typeof el.className === 'string' ? el.className : '',
      r: rectOf(el)
    }
    var text = el.childNodes && el.childNodes.length === 1 && el.firstChild && el.firstChild.nodeType === 3 ? String(el.firstChild.nodeValue || '').trim() : ''
    if (text) item.x = text.slice(0, 40)
    var attrs = ['data-sidebar-collapsed', 'data-rightbar-collapsed', 'data-phase', 'data-conversation-scroll', 'aria-expanded', 'role']
    for (var i = 0; i < attrs.length; i += 1) {
      if (el.hasAttribute && el.hasAttribute(attrs[i])) item.a = (item.a || '') + attrs[i] + ';'
    }
    var children = []
    var kids = el.children || []
    for (var j = 0; j < kids.length && j < 14; j += 1) {
      var child = outline(kids[j], depth + 1, budget)
      if (child !== null) children.push(child)
    }
    if (children.length > 0) item.ch = children
    return item
  }

  /** Send one diagnostics payload to the dev proxy (no-op outside dev). */
  function sendDiag(path, payload) {
    try {
      var body = JSON.stringify(payload)
      if (typeof fetch === 'function') {
        fetch(path, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: body,
          keepalive: true
        }).catch(function () {})
      }
    } catch (error) {
      /* diagnostics must never break the UI */
    }
  }

  /** Report a geometry snapshot of the current layout (dev proxy only). */
  function reportDiag(reason) {
    var diag = state.diag
    if (!diag) return
    /* Only the boot-time snapshots are one-shot; interaction snapshots must be
     * recorded every time, otherwise the second menu/info tap of a session is
     * invisible to whoever is reading the diagnostics. */
    var oneShot = reason === 'apply' || reason === 'settle'
    if (oneShot && state.diagPhase[reason]) return
    state.diagPhase[reason] = true
    var selectors = [
      '.pI_x6G_frame',
      '.pI_x6G_sidebarCol',
      '.pI_x6G_centerCol',
      '.pI_x6G_rightbarCol',
      '.hHd-Xa_root',
      '.wSkVaW_root',
      '.wSkVaW_header',
      '.wSkVaW_titleRow',
      '.wSkVaW_tabs',
      '.wSkVaW_scrollBody',
      '.EvIC1a_scroll',
      '.EvIC1a_column',
      '.uV2eYG_root',
      '.uV2eYG_card',
      '.uV2eYG_row',
      '.uV2eYG_input',
      '.pI_x6G_overlayLayer',
      '.wSkVaW_headerActions',
      '.wSkVaW_headerUtilities',
      '[class*="_panelBody"]',
      '[class*="_tabBody"]',
      DIALOG_SELECTOR,
      '#dshm-panel',
      '#dshm-bar',
      '#dshm-title'
    ]
    var rects = {}
    for (var i = 0; i < selectors.length; i += 1) {
      var el = q(selectors[i])
      if (el) rects[selectors[i]] = rectOf(el)
    }
    var frameEl = frame()
    sendDiag('/__diag', {
      kind: 'report',
      reason: reason,
      version: VERSION,
      viewport: {
        w: window.innerWidth,
        h: window.innerHeight,
        vv: window.visualViewport ? [num(window.visualViewport.width), num(window.visualViewport.height)] : null,
        dpr: window.devicePixelRatio || 1
      },
      ua: navigator.userAgent,
      state: {
        drawer: state.drawer,
        info: state.info,
        sidebarCollapsed: sidebarCollapsed(),
        sidebarRootClass: (function () {
          var root = q('.pI_x6G_sidebarCol .dcu-root') || q('.pI_x6G_sidebarCol .hHd-Xa_root')
          return root === null ? null : String(root.className)
        })(),
        title: state.titleText
      },
      interaction: {
        lastCloseReason: state.lastCloseReason,
        lastClick: state.lastClick,
        clickTrace: state.clickTrace.slice(),
        frameAttrs: frameAttrSignature()
      },
      dialogs: captureDialogs(),
      floats: captureFloats(),
      paneApi: paneApiReport(),
      infoPanel: state.info ? captureInfoPanel() : null,
      rects: rects,
      frameGrid: frameEl ? getComputedStyle(frameEl).gridTemplateColumns : null,
      outline: frameEl ? outline(frameEl, 0, { left: 220 }) : null
    })
  }

  /* ────────────────────────────── lifecycle ────────────────────────────── */

  /** Poll the dev proxy for a newer build of the sources (dev only). */
  function startDevReload() {
    var diag = state.diag
    if (!diag || !diag.ver) return
    var current = diag.rev || ''
    var check = function () {
      try {
        fetch(diag.ver + '?_=' + Date.now(), { cache: 'no-store' })
          .then(function (response) {
            return response.text()
          })
          .then(function (next) {
            if (next && current && next !== current) window.location.reload()
            else current = next || current
          })
          .catch(function () {})
      } catch (error) {
        /* ignore */
      }
    }
    window.setInterval(check, 1500)
  }

  /** Attach document-level observers; returns a disposer. */
  function startObservers() {
    var observer = null
    try {
      if (typeof MutationObserver === 'function') {
        observer = new MutationObserver(function () {
          sync()
        })
        observer.observe(document.body, { childList: true, subtree: true, characterData: true })
      }
    } catch (error) {
      observer = null
    }
    state.timer = window.setInterval(sync, 600)
    state.observer = observer
    return function disposeObservers() {
      if (state.observer) state.observer.disconnect()
      state.observer = null
      if (state.timer !== null) window.clearInterval(state.timer)
      state.timer = null
    }
  }

  /** Wait until the shipped UI has mounted its frame, then initialize the chrome. */
  function waitForFrame(callback) {
    var started = Date.now()
    var attempt = function () {
      if (isMobile() && frame() !== null) {
        callback()
        return
      }
      if (Date.now() - started > 15000) {
        sendDiag('/__log', { kind: 'wait-for-frame-timeout' })
        return
      }
      window.setTimeout(attempt, 120)
    }
    attempt()
  }

  /** Plugin entry: install the mobile layer. */
  function apply(ctx, css, diag) {
    if (handleUrlSwitch()) return
    if (skinDisabled()) {
      try {
        console.warn('[dsh-mobile-ui] rolled back by user; stock UI in effect')
      } catch (ignored) {}
      return
    }
    if (state.applied) {
      if (css && css !== state.css) {
        state.css = css
        injectCss(css)
      }
      return
    }
    if (!isMobile()) return
    consultHostState(function (hostDisabled) {
      if (hostDisabled) {
        try {
          console.warn('[dsh-mobile-ui] host rollback flag set; stock UI in effect')
        } catch (ignored) {}
        return
      }
      startApplication(ctx, css, diag)
    })
    return null
  }

  /** Mount the whole skin. Split out so the host flag can gate it. */
  function startApplication(ctx, css, diag) {
    if (state.applied) return null
    if (skinDisabled()) return null
    state.applied = true
    state.ctx = ctx || null
    state.css = css || ''
    state.diag = diag || null
    injectCss(state.css)
    document.documentElement.setAttribute('data-dsh-mobile', '')
    document.documentElement.setAttribute('data-dshm-drawer', 'closed')
    document.documentElement.setAttribute('data-dshm-info', 'closed')
    document.documentElement.setAttribute('data-dshm-pane', 'closed')
    document.documentElement.setAttribute('data-dshm-modal', 'closed')

    var disposeObservers = null
    waitForFrame(function () {
      state.available = true
      mountChrome()
      /* Restore the right pane the user left open in a previous load. The
       * engine mounts the column hidden on narrow screens, so this layer is the
       * only thing that can bring it back. */
      if (storedPaneOpen()) {
        window.setTimeout(function () {
          if (!rightPaneOpen()) toggleRightPane()
        }, 700)
      }
      try {
        console.warn('[dsh-mobile-ui] v' + VERSION + ' applied at ' + window.innerWidth + 'x' + window.innerHeight)
      } catch (ignored) {}
      disposeObservers = startObservers()
      reportDiag('apply')
      window.setTimeout(function () {
        reportDiag('settle')
      }, 900)
    })

    if (diag && diag.ver) startDevReload()

    var dispose = function dispose() {
      try {
        console.warn('[dsh-mobile-ui] disposed')
      } catch (ignored) {}
      state.available = false
      state.applied = false
      if (disposeObservers) disposeObservers()
      var ids = ['dshm-bar', 'dshm-scrim', 'dshm-info-scrim']
      for (var i = 0; i < ids.length; i += 1) {
        var el = document.getElementById(ids[i])
        if (el !== null && el.parentNode !== null) el.parentNode.removeChild(el)
      }
      var style = document.getElementById('dsh-mobile-ui/style')
      if (style !== null && style.parentNode !== null) style.parentNode.removeChild(style)
      document.documentElement.removeAttribute('data-dsh-mobile')
      document.documentElement.removeAttribute('data-dshm-drawer')
      document.documentElement.removeAttribute('data-dshm-info')
      state.bar = null
      state.scrim = null
      state.infoScrim = null
    }

    state.dispose = dispose

    if (ctx && typeof ctx.effect === 'function') {
      ctx.effect(function () {
        return dispose
      }, 'dsh-mobile-ui: mobile chrome')
    }
    return dispose
  }

  return {
    apply: apply,
    version: VERSION,
    isMobile: isMobile
  }
}
