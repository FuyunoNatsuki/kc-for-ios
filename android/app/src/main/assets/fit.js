/* メインフレーム用：ゲーム枠 (#game_frame, 1200x720) を画面いっぱいにフィットさせる */
(function () {
  if (window.__kanFit) return;
  var W = 1200, H = 720;
  var state = { on: true, off: 0 };
  var timer = null;

  function post(found) {
    try { if (window.kanbridge) window.kanbridge.postMessage(JSON.stringify({ type: 'fit', found: found })); } catch (e) {}
  }
  function setViewport(content) {
    var m = document.querySelector('meta[name=viewport]');
    if (!m) { m = document.createElement('meta'); m.name = 'viewport'; document.head.appendChild(m); }
    m.content = content;
  }
  /* ゲーム以外の部分（広告・おすすめ枠など）を止める。
     visibility:hidden だけだと裏で動き続けてメモリと電池を使うため、
     ほかの iframe は外し、ゲーム枠の祖先以外は display:none にする。 */
  function trim(f) {
    var frames = document.getElementsByTagName('iframe');
    for (var i = frames.length - 1; i >= 0; i--) {
      var fr = frames[i];
      if (fr === f || fr.contains(f)) continue;
      try { fr.src = 'about:blank'; } catch (e) {}
      if (fr.parentNode) fr.parentNode.removeChild(fr);
    }
    var node = f;
    while (node && node !== document.body && node.parentNode) {
      var p = node.parentNode;
      for (var c = p.firstElementChild; c; c = c.nextElementSibling) {
        if (c === node || c.tagName === 'SCRIPT' || c.tagName === 'STYLE' || c.tagName === 'LINK') continue;
        if (c.hasAttribute('data-kan-hidden')) continue;
        c.setAttribute('data-kan-hidden', c.style.getPropertyValue('display'));
        c.style.setProperty('display', 'none', 'important');
      }
      node = p;
    }
  }
  function untrim() {
    var list = document.querySelectorAll('[data-kan-hidden]');
    for (var i = 0; i < list.length; i++) {
      var c = list[i], v = c.getAttribute('data-kan-hidden');
      c.removeAttribute('data-kan-hidden');
      if (v) c.style.setProperty('display', v); else c.style.removeProperty('display');
    }
  }
  function apply() {
    var f = document.getElementById('game_frame');
    var st = document.getElementById('__kanfit_style');
    if (!state.on) {
      if (st) st.remove();
      untrim();
      if (f) setViewport('width=1240');
      post(false);
      return true;
    }
    if (!f) { post(false); return false; }
    trim(f);
    setViewport('width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover');
    if (!st) { st = document.createElement('style'); st.id = '__kanfit_style'; document.head.appendChild(st); }
    var vw = window.innerWidth, vh = window.innerHeight;
    var off = state.off;
    var s = Math.min(vw / W, vh / H);
    var l = (vw - W * s) / 2, t = (vh - H * s) / 2;
    st.textContent =
      'html,body{overflow:hidden!important;background:#000!important;margin:0!important}' +
      'body *{visibility:hidden!important}' +
      '#game_frame{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;' +
      'width:' + W + 'px!important;height:' + (H + Math.max(off, 0)) + 'px!important;' +
      'margin:0!important;border:0!important;max-width:none!important;' +
      'transform-origin:0 0!important;' +
      'transform:translate(' + l + 'px,' + t + 'px) scale(' + s + ') translateY(' + (-off) + 'px)!important;' +
      'z-index:2147483647!important}';
    window.scrollTo(0, 0);
    post(true);
    return true;
  }
  window.__kanFit = function (on, off) {
    state.on = on; state.off = off || 0;
    if (timer) { clearInterval(timer); timer = null; }
    if (apply()) return;
    var n = 0;
    timer = setInterval(function () {
      if (apply() || ++n > 120) { clearInterval(timer); timer = null; }
    }, 500);
  };
  window.addEventListener('resize', function () { setTimeout(apply, 50); setTimeout(apply, 400); });
})();
