(function () {
  try {
    var ok = !!document.getElementById('dshm-bar') ||
             document.documentElement.hasAttribute('data-dsh-mobile')
    return ok ? 'ok' : 'missing'
  } catch (e) {
    return 'error'
  }
})()
