(function () {
  try {
    var ua = navigator.userAgent || '';
    var m = ua.match(/Chrome\/(\d+)/);
    var chrome = m ? parseInt(m[1], 10) : 0;
    var need = {
      'Object.hasOwn': 93, 'replaceChildren': 86, 'replaceAll': 85,
      'findLast': 97, 'structuredClone': 98, 'toSorted': 110,
      'AbortSignal.any': 116, 'Promise.withResolvers': 119, 'URL.parse': 126
    };
    var missing = [];
    var probes = {
      'Object.hasOwn': function(){ return typeof Object.hasOwn === 'function'; },
      'replaceChildren': function(){ return typeof Element !== 'undefined' && !!Element.prototype.replaceChildren; },
      'replaceAll': function(){ return !!String.prototype.replaceAll; },
      'findLast': function(){ return !!Array.prototype.findLast; },
      'structuredClone': function(){ return typeof structuredClone === 'function'; },
      'toSorted': function(){ return !!Array.prototype.toSorted; },
      'AbortSignal.any': function(){ return typeof AbortSignal !== 'undefined' && !!AbortSignal.any; },
      'Promise.withResolvers': function(){ return !!Promise.withResolvers; },
      'URL.parse': function(){ return typeof URL !== 'undefined' && !!URL.parse; }
    };
    for (var k in probes) { try { if (!probes[k]()) missing.push(k + '(need ' + need[k] + ')'); } catch (e) { missing.push(k + '(probe err)'); } }
    return '[dsh-android] webview ok | chrome=' + chrome +
      ' | polyfill=' + (!missing.length ? 'complete' : 'MISSING: ' + missing.join(',')) +
      ' | ua=' + ua.slice(0, 120);
  } catch (e) { return '[dsh-android] selfcheck failed: ' + e.message; }
})();
