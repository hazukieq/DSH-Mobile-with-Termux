(function(){
  try {
    var t = __DSH_TEXT__;
    var el = window.__dshEl && document.body.contains(window.__dshEl)
      ? window.__dshEl
      : (document.querySelector('textarea')
         || document.querySelector('[contenteditable="true"]')
         || document.querySelector('input[type=text]'));
    if (!el) return 'no-input';
    el.focus();
    function contentOf(node) { return (node.textContent || '').trim(); }
    function selectAllIn(node) {
      try {
        var sel = window.getSelection();
        var range = document.createRange();
        range.selectNodeContents(node);
        sel.removeAllRanges();
        sel.addRange(range);
      } catch (e) {}
    }
    selectAllIn(el);
    try { document.execCommand('insertText', false, t); } catch (e) {}
    if (contentOf(el) !== t.trim() && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT')) {
      var proto = el.tagName === 'TEXTAREA'
        ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, t);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
    return 'repaired:' + contentOf(el).slice(0, 40);
  } catch (e) { return 'err:' + e.message; }
})();
