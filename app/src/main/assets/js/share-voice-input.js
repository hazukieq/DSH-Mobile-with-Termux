(function(){
  try {
    var t = __DSH_TEXT__;
    // v1.2.65: ensure there is an input box to write into. The user may be sitting
    // on the home screen (no conversation open), where no textarea exists; the old
    // build returned 'no-input-found' and the send silently did nothing.
    // Here we first look for a conversation entry in the DOM, click into it, and
    // let the caller retry once the UI has rendered.
    function findInput() {
      return document.querySelector('textarea')
          || document.querySelector('[contenteditable="true"]')
          || document.querySelector('input[type=text]');
    }
    // Send button: exact selectors first, then a fuzzy sweep over buttons whose
    // aria-label/class mentions send. The old build tried three exact selectors and
    // silently fell back to Enter; if Enter inserts a newline in this frontend,
    // the message never leaves the box (user-reported "it does not send").
    function findSend(el) {
      var sels = ['button[aria-label*="send" i]', 'button[aria-label*="\u53d1\u9001"]',
                  '[data-testid*="send" i]'];
      for (var i = 0; i < sels.length; i++) {
        var b = document.querySelector(sels[i]);
        if (b && b.disabled !== true) return b;
      }
      var cands = document.querySelectorAll('button, [role="button"]');
      for (var k = cands.length - 1; k >= 0; k--) {
        var c = cands[k];
        var label = (c.getAttribute('aria-label') || '') + ' ' + (c.className || '');
        if (/send|\u53d1\u9001/i.test(label) && c.disabled !== true) return c;
      }
      // Fallback: the last enabled button inside the input's own container
      var scope = el.closest('form') || (el.parentElement && el.parentElement.parentElement);
      if (scope) {
        var btns = scope.querySelectorAll('button');
        for (var j = btns.length - 1; j >= 0; j--) {
          if (btns[j].disabled !== true) return btns[j];
        }
      }
      return null;
    }
    var el = findInput();
    if (!el) {
      var cand = document.querySelector('[data-session-id]')
              || document.querySelector('a[href*="session"]')
              || document.querySelector('[role="listitem"]')
              || document.querySelector('[class*="session" i]');
      if (cand instanceof HTMLElement) {
        cand.click();
        return 'entering-session';
      }
      return 'no-input-found';
    }
    el.focus();
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      var proto = el.tagName === 'TEXTAREA'
        ? window.HTMLTextAreaElement.prototype
        : window.HTMLInputElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, t);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    } else {
      // contenteditable (rich-text editors: Lexical / ProseMirror / Quill ...).
      //
      // v1.2.95 - REWRITE after live reproduce on the real WebUI (emulator + CDP).
      // Facts established by experiment:
      //  1) execCommand('insertText') on this Lexical editor DOUBLED the text: the
      //     framework imports the DOM mutation AND applies the beforeinput that
      //     execCommand fires. One write became two.
      //  2) Dispatching cancelable 'beforeinput' ALSO doubled.
      //  3) A synthetic ClipboardEvent('paste') with a DataTransfer is processed
      //     by Lexical's native paste pipeline EXACTLY ONCE - but ASYNCHRONOUSLY:
      //     the content lands a beat later. A synchronous check right after the
      //     dispatch still sees the old content, which made the old in-JS fallback
      //     kick in and double the text anyway.
      // So this snippet does ONE thing only: fire the paste. Whether the write
      // landed is checked by the Kotlin caller (readSendText), which falls back
      // to buildVoiceRepairJs (execCommand, safe for framework-less editors)
      // when the paste had no effect. No in-JS fallback here - any
      // synchronous second write races the paste and doubles the text.
      el.focus();
      // Ctrl+A keydown lets the editor select its own content first.
      el.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'a', code: 'KeyA', keyCode: 65, ctrlKey: true, bubbles: true,
      }));
      var dt = new DataTransfer();
      dt.setData('text/plain', t);
      el.dispatchEvent(new ClipboardEvent('paste', {
        bubbles: true, cancelable: true, clipboardData: dt,
      }));
    }
    // Do NOT click send synchronously: a React controlled input updates its state
    // asynchronously, so right after dispatching the input event the send button may
    // still be disabled and the click is a no-op.
    //
    // v1.2.92 - regression fix. v1.2.88 deferred that click with
    // requestAnimationFrame to shave latency, but rAF callbacks ride on frame
    // production and a backgrounded WebView produces no frames: tapping "send"
    // while the app was in the background left the click queued, so the message
    // only went out the instant the user returned to the foreground (user: "it
    // only sends the moment I come back"). The pre-v1.2.88 setTimeout version
    // did work in the background.
    //
    // Now the app side owns both the commit and the verdict: we expose two
    // synchronous hooks and Kotlin calls them on its own Handler schedule, so no
    // JS timer and no frame is involved and background behaves like foreground.
    window.__dshEl = el;
    // One-shot ticket, handed out per write (see __dshFire). Not a time window:
    // v1.2.92 throttled by elapsed time, and Kotlin's "still in the box after 4s,
    // fire again" retry sailed straight through it (4s > 1.5s) - when the
    // frontend was slow to clear the box that second click sent the same text
    // again, which is exactly the "two copies" the user kept seeing.
    window.__dshTicket = false;
    window.__dshFire = function () {
      if (!window.__dshTicket) return 'consumed';   // already committed this text
      var target = (window.__dshEl && document.body.contains(window.__dshEl))
        ? window.__dshEl : findInput();
      if (!target) return 'no-input';
      var v = (target.tagName === 'TEXTAREA' || target.tagName === 'INPUT')
        ? (target.value || '') : (target.textContent || '');
      if (!v.trim()) return 'empty';   // nothing to send - never click on empty
      var btn = findSend(target);
      // Spend the ticket BEFORE clicking: whatever happens next, this text gets
      // exactly one commit attempt. Retrying a submit that already reached the
      // frontend is what produced duplicate messages.
      window.__dshTicket = false;
      if (btn) { btn.click(); return 'clicked'; }
      target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
      target.dispatchEvent(new KeyboardEvent('keyup',   { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
      return 'enter';
    };
    // Verdict source: the frontend clears the input once the message is committed.
    window.__dshReadVal = function () {
      var e = window.__dshEl;
      if (!e) return 'none';
      if (!document.body.contains(e)) return 'gone';
      var v = (e.tagName === 'TEXTAREA' || e.tagName === 'INPUT') ? (e.value || '') : (e.textContent || '');
      return v.trim() ? 'text' : 'empty';
    };
    // Actual text in the box. Kotlin compares it against what it tried to send:
    // only a match means "this is our text, safe to (re)submit"; anything else is
    // the user's own typing and must be left alone.
    window.__dshText = function () {
      var e = window.__dshEl;
      if (!e) {
        var f = findInput();
        if (!f) return '';
        e = f;
      }
      if (!document.body.contains(e)) return '';
      var v = (e.tagName === 'TEXTAREA' || e.tagName === 'INPUT') ? (e.value || '') : (e.textContent || '');
      return v.trim();
    };
    // Hand out exactly one commit ticket for the text just written. Kotlin calls
    // __dshFire() later (250ms, on its own Handler) to spend it.
    // ⚠️ Grant MUST come after the hook block above: that block re-initialises
    // __dshTicket = false, and in v1.2.94 the grant sat before it - the reset
    // clobbered the grant, every fire returned 'consumed' and nothing was ever
    // submitted (the text just sat in the box). This order is load-bearing.
    window.__dshTicket = true;
    return 'ok:written';
  } catch (e) { return 'err:' + e.message; }
})();
