/* 全フレームに document 開始時に注入。
   1) ゲームのAPI応答を読み取ってアプリに渡す（通信内容は変更しない・トークンは除去）
   2) 長押しメニュー・文字選択・タップ時のハイライトを抑止 */
(function () {
  if (window.__kanApiHooked) return;
  window.__kanApiHooked = true;

  function send(o) {
    try { if (window.kanbridge) window.kanbridge.postMessage(JSON.stringify(o)); } catch (e) {}
  }
  window.__kanSend = send;

  function post(url, body, text) {
    try {
      var i = url.indexOf('/kcsapi/');
      if (i < 0 || !text) return;
      var path = url.substring(i + 8).split('?')[0];
      var b = (body == null) ? '' : String(body);
      b = b.replace(/(^|&)api_token=[^&]*/g, '').replace(/(^|&)api_verno=[^&]*/g, '');
      send({ type: 'api', path: path, body: b, text: text });
    } catch (e) {}
  }

  var P = XMLHttpRequest.prototype, open = P.open, sendXhr = P.send;
  P.open = function (m, u) { this.__kanUrl = String(u); return open.apply(this, arguments); };
  P.send = function (body) {
    var x = this;
    if (x.__kanUrl && x.__kanUrl.indexOf('/kcsapi/') >= 0) {
      x.addEventListener('load', function () {
        var t = '';
        try {
          t = (x.responseType === '' || x.responseType === 'text') ? x.responseText : JSON.stringify(x.response);
        } catch (e) {}
        post(x.__kanUrl, body, t);
      });
    }
    return sendXhr.apply(this, arguments);
  };

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
