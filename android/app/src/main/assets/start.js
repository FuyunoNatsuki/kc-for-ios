/* 全フレームに document 開始時に注入。
   1) ゲームのAPI応答を読み取ってアプリに渡す（通信内容は変更しない・トークンは除去）
      アプリが使うAPIだけを渡し、それ以外の大きな応答は転送しない
   2) 長押しメニュー・文字選択・タップ時のハイライトを抑止
   3) 描画のフレームレートを上限 window.__kanCap fps に抑える（発熱による性能低下を防ぐ。0 = 制限なし） */
(function () {
  if (window.__kanApiHooked) return;
  window.__kanApiHooked = true;

  function send(o) {
    try { if (window.kanbridge) window.kanbridge.postMessage(JSON.stringify(o)); } catch (e) {}
  }
  window.__kanSend = send;

  var WANT = new RegExp('^(api_start2/getData|api_port/port|api_get_member/(ship_deck|ship2|ship3|deck|ndock|kdock|questlist|require_info|slot_item)' +
    '|api_req_kousyou/(getship|createitem|remodel_slot|destroyitem2)|api_req_hokyu/charge|api_req_nyukyo/(start|speedchange)' +
    '|api_req_quest/(clearitemget|stop)|api_req_map/(start|next)|api_req_sortie/|api_req_battle_midnight/|api_req_combined_battle/)');

  function post(path, body, text) {
    try {
      var b = (body == null) ? '' : String(body);
      b = b.replace(/(^|&)api_token=[^&]*/g, '').replace(/(^|&)api_verno=[^&]*/g, '');
      send({ type: 'api', path: path, body: b, text: text });
    } catch (e) {}
  }

  var P = XMLHttpRequest.prototype, open = P.open, sendXhr = P.send;
  P.open = function (m, u) { this.__kanUrl = String(u); return open.apply(this, arguments); };
  P.send = function (body) {
    var x = this, u = x.__kanUrl || '', i = u.indexOf('/kcsapi/');
    if (i >= 0) {
      var path = u.substring(i + 8).split('?')[0];
      if (WANT.test(path)) {
        x.addEventListener('load', function onload() {
          x.removeEventListener('load', onload);
          var t = '';
          try {
            t = (x.responseType === '' || x.responseType === 'text') ? x.responseText : JSON.stringify(x.response);
          } catch (e) {}
          if (t) post(path, body, t);
        });
      }
    }
    return sendXhr.apply(this, arguments);
  };

  /* requestAnimationFrame を間引いてフレームレートを抑える。
     ゲームは経過時間でアニメーションするので、速さは変わらず滑らかさだけが下がる。 */
  (function () {
    var cap = (typeof window.__kanCap === 'number') ? window.__kanCap : 30;
    var raf = window.requestAnimationFrame && window.requestAnimationFrame.bind(window);
    if (!raf || cap <= 0) return;
    var minGap = 1000 / cap - 2;
    var queue = {}, nextId = 1, scheduled = false, last = 0;
    function tick(ts) {
      if (ts - last < minGap) { raf(tick); return; }
      last = ts; scheduled = false;
      var q = queue; queue = {};
      for (var id in q) {
        try { q[id](ts); } catch (e) { setTimeout(function (err) { return function () { throw err; }; }(e)); }
      }
    }
    window.requestAnimationFrame = function (cb) {
      var id = nextId++;
      queue[id] = cb;
      if (!scheduled) { scheduled = true; raf(tick); }
      return id;
    };
    window.cancelAnimationFrame = function (id) { delete queue[id]; };
  })();

  function style() {
    var s = document.createElement('style');
    s.textContent =
      '*:not(input):not(textarea){-webkit-user-select:none!important;user-select:none!important;-webkit-touch-callout:none!important}' +
      '*{-webkit-tap-highlight-color:transparent!important;touch-action:manipulation}';
    (document.head || document.documentElement).appendChild(s);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', style);
  else style();
})();
