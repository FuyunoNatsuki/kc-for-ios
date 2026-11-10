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

    /// アプリが使うAPIだけをアプリに渡す（それ以外の大きな応答は転送しない）
    static let apiPattern =
        "^(api_start2/getData|api_port/port|api_get_member/(ship_deck|ship2|ship3|deck|ndock|kdock|questlist|require_info|slot_item)"
        + "|api_req_kousyou/(getship|createitem|remodel_slot|destroyitem2)|api_req_hokyu/charge|api_req_nyukyo/(start|speedchange)"
        + "|api_req_quest/(clearitemget|stop)|api_req_map/(start|next)|api_req_sortie/|api_req_battle_midnight/|api_req_combined_battle/)"

    /// 全フレーム用（document開始時）
    /// 1) ゲームのAPI応答を読み取ってアプリに渡す（通信内容は一切変更しない。トークンは取り除く）
    /// 2) 描画のフレームレートを上限 cap fps に抑える（発熱による性能低下を防ぐ。0 = 制限なし）
    /// 3) 実際のフレームレートを2秒ごとにアプリへ知らせる（診断用）
    static func apiCapture(cap: Int) -> String {
        """
        (function(){
          if (window.__kanApiHooked) return;
          window.__kanApiHooked = true;
          var WANT = new RegExp('\(apiPattern)');
          function post(path, body, text){
            try {
              var b = (body == null) ? '' : String(body);
              b = b.replace(/(^|&)api_token=[^&]*/g, '').replace(/(^|&)api_verno=[^&]*/g, '');
              window.webkit.messageHandlers.kanapi.postMessage({ path: path, body: b, text: text });
            } catch (e) {}
          }
          var P = XMLHttpRequest.prototype, open = P.open, send = P.send;
          P.open = function(m, u){ this.__kanUrl = String(u); return open.apply(this, arguments); };
          P.send = function(body){
            var x = this, u = x.__kanUrl || '', i = u.indexOf('/kcsapi/');
            if (i >= 0) {
              var path = u.substring(i + 8).split('?')[0];
              if (WANT.test(path)) {
                x.addEventListener('load', function onload(){
                  x.removeEventListener('load', onload);
                  var t = '';
                  try {
                    t = (x.responseType === '' || x.responseType === 'text') ? x.responseText : JSON.stringify(x.response);
                  } catch (e) {}
                  if (t) post(path, body, t);
                });
              }
            }
            return send.apply(this, arguments);
          };

          /* requestAnimationFrame を間引いてフレームレートを抑える。
             ゲームは経過時間でアニメーションするので、速さは変わらず滑らかさだけが下がる。 */
          var cap = \(cap);
          var raf = window.requestAnimationFrame && window.requestAnimationFrame.bind(window);
          if (!raf) return;
          var minGap = cap > 0 ? 1000 / cap - 2 : 0;
          var queue = {}, nextId = 1, pending = 0, scheduled = false, last = 0, frames = 0;
          function tick(ts){
            if (minGap > 0 && ts - last < minGap) { raf(tick); return; }
            last = ts; scheduled = false;
            var q = queue; queue = {}; pending = 0;
            var ran = false;
            for (var id in q) {
              ran = true;
              try { q[id](ts); } catch (e) { setTimeout(function(err){ return function(){ throw err; }; }(e)); }
            }
            if (ran) frames++;
          }
          window.requestAnimationFrame = function(cb){
            var id = nextId++;
            queue[id] = cb; pending++;
            if (!scheduled) { scheduled = true; raf(tick); }
            return id;
          };
          window.cancelAnimationFrame = function(id){
            if (queue[id]) { delete queue[id]; pending--; }
          };
          setInterval(function(){
            if (frames > 0) {
              try { window.webkit.messageHandlers.kanfps.postMessage(Math.round(frames / 2)); } catch (e) {}
            }
            frames = 0;
          }, 2000);
        })();
        """
    }

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
      /* ゲーム以外の部分（広告・おすすめ枠など）を止める。
         visibility:hidden だけだと裏で動き続けてメモリと電池を使うため、
         ほかの iframe は外し、ゲーム枠の祖先以外は display:none にする。 */
      function trim(f){
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
      function untrim(){
        var list = document.querySelectorAll('[data-kan-hidden]');
        for (var i = 0; i < list.length; i++) {
          var c = list[i], v = c.getAttribute('data-kan-hidden');
          c.removeAttribute('data-kan-hidden');
          if (v) c.style.setProperty('display', v); else c.style.removeProperty('display');
        }
      }
      function apply(){
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
