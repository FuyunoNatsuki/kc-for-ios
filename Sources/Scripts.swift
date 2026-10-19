import Foundation

/// ページに注入する JavaScript。
/// ゲームの通信やデータには一切触れず、「表示の仕方」だけを変える。
enum Scripts {

    /// 全フレーム（ゲーム内 iframe 含む）用：長押しメニュー・文字選択・ダブルタップズームを抑止
    static let allFrames = """
    (function(){
      var s = document.createElement('style');
      s.textContent =
        '*:not(input):not(textarea){-webkit-user-select:none!important;-webkit-touch-callout:none!important}' +
        '*{-webkit-tap-highlight-color:transparent!important;touch-action:manipulation}';
      (document.head || document.documentElement).appendChild(s);
    })();
    """

    /// 全フレーム用（document開始時）：ゲームのAPI応答を読み取ってアプリに渡す。
    /// 通信内容は一切変更しない。トークンはアプリに渡す前に取り除く。
    static let apiCapture = """
    (function(){
      if (window.__kanApiHooked) return;
      window.__kanApiHooked = true;
      function post(url, body, text){
        try {
          var i = url.indexOf('/kcsapi/');
          if (i < 0 || !text) return;
          var path = url.substring(i + 8).split('?')[0];
          var b = (body == null) ? '' : String(body);
          b = b.replace(/(^|&)api_token=[^&]*/g, '').replace(/(^|&)api_verno=[^&]*/g, '');
          window.webkit.messageHandlers.kanapi.postMessage({ path: path, body: b, text: text });
        } catch (e) {}
      }
      var P = XMLHttpRequest.prototype, open = P.open, send = P.send;
      P.open = function(m, u){ this.__kanUrl = String(u); return open.apply(this, arguments); };
      P.send = function(body){
        var x = this;
        if (x.__kanUrl && x.__kanUrl.indexOf('/kcsapi/') >= 0) {
          x.addEventListener('load', function(){
            var t = '';
            try {
              t = (x.responseType === '' || x.responseType === 'text') ? x.responseText : JSON.stringify(x.response);
            } catch (e) {}
            post(x.__kanUrl, body, t);
          });
        }
        return send.apply(this, arguments);
      };
    })();
    """

    /// メインフレーム用：ゲーム枠 (#game_frame, 1200x720) を画面いっぱいにフィットさせる
    static let mainFrame = """
    (function(){
      if (window.__kanFit) return;
      var W = 1200, H = 720;
      var state = { on: true, off: 0 };
      var timer = null;

      function post(found){
        try { window.webkit.messageHandlers.kanfit.postMessage(found); } catch(e) {}
      }
      function setViewport(content){
        var m = document.querySelector('meta[name=viewport]');
        if (!m) { m = document.createElement('meta'); m.name = 'viewport'; document.head.appendChild(m); }
        m.content = content;
      }
      function apply(){
        var f = document.getElementById('game_frame');
        var st = document.getElementById('__kanfit_style');
        if (!state.on) {
          if (st) st.remove();
          if (f) setViewport('width=1240');
          post(false);
          return true;
        }
        if (!f) { post(false); return false; }
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
        post(true);
        return true;
      }
      window.__kanFit = function(on, off){
        state.on = on; state.off = off || 0;
        if (timer) { clearInterval(timer); timer = null; }
        if (apply()) return;
        var n = 0;  // ゲーム枠が後から挿入される場合に備えて最大60秒待つ
        timer = setInterval(function(){
          if (apply() || ++n > 120) { clearInterval(timer); timer = null; }
        }, 500);
      };
      window.addEventListener('resize', function(){ setTimeout(apply, 50); });
      window.addEventListener('orientationchange', function(){ setTimeout(apply, 300); });
    })();
    """
}
