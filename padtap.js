/* PadTap — ゲームパッド → 画面タップ変換（ブラウザゲーム用ブックマークレット）
 * スティック/十字キー: 画面上のカーソルを動かす
 * カーソル用ボタン(初期: A): カーソル位置をタップ（押しっぱなし＝ホールド/ドラッグ）
 * その他のボタン: 設定した画面位置をタップ
 */
(() => {
  if (window.__padtap) { window.__padtap.open(); return; }
  const W = window, D = document;
  const KEY = 'padtap:' + location.host + location.pathname;
  const GKEY = 'padtap:g';
  const NAMES = ['A','B','X','Y','LB','RB','LT','RT','SELECT','START','L3','R3','↑','↓','←','→','HOME'];
  const nm = b => NAMES[b] || 'B' + b;
  const DPAD = [12, 13, 14, 15];
  const store = {
    get(k, d) { try { const v = JSON.parse(localStorage.getItem(k)); return v == null ? d : v; } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }
  };
  let map = store.get(KEY, { 0: 'cursor' });
  const g = Object.assign({ speed: 1, cursor: true, guard: true, marks: false, hx: 1, hy: 0.4 }, store.get(GKEY, {}));
  const saveMap = () => store.set(KEY, map);
  const saveG = () => store.set(GKEY, g);

  /* ---------- UI ---------- */
  const host = D.createElement('div');
  host.style.cssText = 'position:fixed;inset:0;z-index:2147483647;pointer-events:none;display:block;';
  const sr = host.attachShadow({ mode: 'open' });
  sr.innerHTML = `<style>
*{box-sizing:border-box;font-family:system-ui,-apple-system,"Noto Sans JP",sans-serif;-webkit-tap-highlight-color:transparent}
#root{position:fixed;inset:0;pointer-events:none;color:#eef;font-size:14px;line-height:1.45}
#catch{position:fixed;inset:0;background:rgba(8,10,18,.38);display:none;pointer-events:auto;touch-action:none}
.on #catch{display:block}
#marks{position:fixed;inset:0;display:none}
.on #marks,.mk #marks{display:block}
.mk:not(.on) #marks{opacity:.35}
.m{position:fixed;width:48px;height:48px;margin:-24px 0 0 -24px;border-radius:50%;background:rgba(255,255,255,.16);border:2px solid #7cf;color:#fff;font:700 12px/44px system-ui;text-align:center;text-shadow:0 1px 2px #000;pointer-events:none;touch-action:none;transition:transform .08s;white-space:nowrap}
.on .m{pointer-events:auto}
.m.sel{border-color:#fd6;background:rgba(255,220,100,.3);transform:scale(1.15)}
.m.hit{transform:scale(.82);background:rgba(120,200,255,.6)}
#cur{position:fixed;left:-99px;top:-99px;width:28px;height:28px;margin:-14px 0 0 -14px;border-radius:50%;border:2px solid #fff;box-shadow:0 0 0 2px rgba(0,0,0,.55),inset 0 0 0 2px rgba(0,0,0,.35);opacity:0;transition:opacity .4s,transform .08s,background .08s}
#cur:after{content:"";position:absolute;left:50%;top:50%;width:4px;height:4px;margin:-2px;border-radius:50%;background:#fff;box-shadow:0 0 0 1px #000}
#cur.v{opacity:1;transition:opacity .1s,transform .08s}
#cur.dn{background:rgba(120,200,255,.55);transform:scale(.78)}
.rp{position:fixed;width:40px;height:40px;margin:-20px 0 0 -20px;border-radius:50%;border:2px solid #7cf;animation:rp .35s ease-out forwards}
@keyframes rp{from{transform:scale(.4);opacity:1}to{transform:scale(1.3);opacity:0}}
#hint{position:fixed;left:50%;top:10px;transform:translateX(-50%);width:max-content;max-width:calc(100vw - 20px);background:rgba(16,18,28,.94);border:1px solid #445;border-radius:14px;padding:10px 12px;display:none;pointer-events:auto;text-align:center;box-shadow:0 6px 24px rgba(0,0,0,.45)}
.on #hint{display:block}
#hint b{color:#fd6}
.sub{font-size:12px;color:#9ab;margin-top:2px}
.row{display:flex;gap:6px;justify-content:center;flex-wrap:wrap;margin-top:8px}
button.t{appearance:none;border:1px solid #556;background:#2a2e40;color:#eef;border-radius:10px;padding:8px 11px;font-size:13px;cursor:pointer}
button.t.p{background:#3b7cff;border-color:#3b7cff;color:#fff}
button.t.d{background:#5a2630;border-color:#83404c}
#hd{position:fixed;width:44px;height:44px;margin:-22px 0 0 -22px;border-radius:50%;border:1px solid rgba(255,255,255,.35);background:rgba(20,22,32,.6);color:#fff;font-size:20px;pointer-events:auto;touch-action:none;padding:0;line-height:42px;text-align:center;opacity:.8}
.on #hd,.hh #hd{display:none}
#panel{position:fixed;right:10px;top:50%;transform:translateY(-50%);width:min(290px,calc(100vw - 20px));max-height:calc(100vh - 20px);overflow:auto;background:rgba(16,18,28,.97);border:1px solid #445;border-radius:16px;padding:14px;display:none;pointer-events:auto;box-shadow:0 10px 30px rgba(0,0,0,.5)}
.pn #panel{display:block}
.on #panel{display:none}
h3{margin:0 0 2px;font-size:16px}
.st{font-size:12px;color:#9ab;margin-bottom:10px;word-break:break-all}
.r{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:8px 0;border-top:1px solid #2a2e40}
.r input[type=checkbox]{width:20px;height:20px;accent-color:#3b7cff}
.r input[type=range]{width:110px;accent-color:#3b7cff}
.col{display:flex;flex-direction:column;gap:8px;margin-top:10px}
.col button,#bSetup{width:100%}
#toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);max-width:calc(100vw - 20px);background:rgba(16,18,28,.94);color:#fff;padding:8px 14px;border-radius:20px;font-size:13px;opacity:0;transition:opacity .25s;text-align:center}
#toast.v{opacity:1}
</style><div id=root><div id=catch></div><div id=marks></div><div id=fx></div><div id=cur></div><div id=hint></div><button id=hd>🎮</button><div id=panel>
<h3>🎮 PadTap</h3><div class=st id=st></div>
<button class="t p" id=bSetup>ボタン配置を設定</button>
<div class=r style="margin-top:10px"><span>カーソル・タップ表示</span><input type=checkbox id=cCur></div>
<div class=r><span>カーソル速度</span><input type=range id=rSp min=0.3 max=3 step=0.1></div>
<div class=r><span>プレイ中も配置を表示</span><input type=checkbox id=cMk></div>
<div class=r><span>「戻る」を防ぐ<div class=sub>Bでページが戻っちゃう時用</div></span><input type=checkbox id=cGd></div>
<div class=col><button class=t id=bHide>アイコンを隠す</button><button class="t d" id=bRst>このゲームの配置をリセット</button><button class=t id=bExit>PadTapを終了</button><button class=t id=bClose>閉じる</button></div>
<div class=sub style="margin-top:10px">SELECT＋START同時押しでも設定を開けるよ</div>
</div><div id=toast></div></div>`;
  const $ = id => sr.getElementById(id);
  const root = $('root'), catcher = $('catch'), marks = $('marks'), fx = $('fx'), curEl = $('cur'),
    hint = $('hint'), hd = $('hd'), panel = $('panel'), st = $('st'), toastEl = $('toast');
  const mount = () => {
    const fe = D.fullscreenElement || D.webkitFullscreenElement;
    const t = fe && !/^(CANVAS|VIDEO|IFRAME|IMG)$/.test(fe.tagName) ? fe : D.documentElement;
    if (host.parentNode !== t) t.appendChild(host);
  };
  mount();
  // オーバーレイ上の操作がゲーム側に漏れないように
  ['pointerdown','pointerup','pointermove','touchstart','touchend','touchmove','mousedown','mouseup','mousemove','click','contextmenu']
    .forEach(t => root.addEventListener(t, e => e.stopPropagation()));

  let tt;
  const toast = s => { toastEl.textContent = s; toastEl.classList.add('v'); clearTimeout(tt); tt = setTimeout(() => toastEl.classList.remove('v'), 2000); };

  /* ---------- 合成タッチ ---------- */
  const fingers = new Map();
  let pid = 11, warnedFrame = false;
  function targetAt(x, y) {
    let doc = D, ox = 0, oy = 0, el = null;
    for (let i = 0; i < 6; i++) {
      let list = [];
      try { list = doc.elementsFromPoint(x - ox, y - oy); } catch (e) {}
      el = list.find(e => e !== host) || doc.body || doc.documentElement;
      if (/^I?FRAME$/.test(el.tagName)) {
        let inner = null;
        try { inner = el.contentDocument; } catch (e) {}
        if (inner && inner.documentElement) {
          const r = el.getBoundingClientRect();
          ox += r.left + el.clientLeft; oy += r.top + el.clientTop; doc = inner; continue;
        }
        if (!warnedFrame) { warnedFrame = true; toast('別サイトのiframe内のゲームは操作できないかも。ゲーム本体のURLを直接開いてみて'); }
      }
      break;
    }
    return { el, doc, ox, oy };
  }
  function ptr(f, type) {
    const C = f.W.PointerEvent || W.PointerEvent; if (!C) return true;
    const end = /up|out|leave/.test(type), lv = type === 'pointerleave';
    return f.el.dispatchEvent(new C(type, {
      bubbles: !lv, cancelable: !lv, composed: true, view: f.W,
      clientX: f.x, clientY: f.y, screenX: f.x, screenY: f.y,
      pointerId: f.id, pointerType: 'touch', isPrimary: f.prim, width: 24, height: 24,
      pressure: end ? 0 : 0.5, button: /down|up/.test(type) ? 0 : -1, buttons: end ? 0 : 1
    }));
  }
  function mkT(f) {
    return new (f.W.Touch || W.Touch)({ identifier: f.id, target: f.el, clientX: f.x, clientY: f.y, screenX: f.x, screenY: f.y,
      pageX: f.x + f.W.scrollX, pageY: f.y + f.W.scrollY, radiusX: 12, radiusY: 12, force: 0.5 });
  }
  function touch(f, type) {
    try {
      const C = f.W.TouchEvent || W.TouchEvent;
      if (!C || !(f.W.Touch || W.Touch)) return true;
      const live = [...fingers.values()].filter(o => o.doc === f.doc);
      return f.el.dispatchEvent(new C(type, {
        bubbles: true, cancelable: true, composed: true, view: f.W,
        touches: live.map(mkT), targetTouches: live.filter(o => o.el === f.el).map(mkT), changedTouches: [mkT(f)]
      }));
    } catch (e) { return true; }
  }
  function mouse(f, type) {
    const C = f.W.MouseEvent || W.MouseEvent;
    f.el.dispatchEvent(new C(type, {
      bubbles: true, cancelable: true, composed: true, view: f.W,
      clientX: f.x, clientY: f.y, screenX: f.x, screenY: f.y, button: 0,
      buttons: f.md && type !== 'mouseup' && type !== 'click' ? 1 : 0, detail: /down|up|click/.test(type) ? 1 : 0
    }));
  }
  function down(k, x, y) {
    if (fingers.has(k)) up(k);
    const t = targetAt(x, y);
    const f = { k, id: pid++, el: t.el, doc: t.doc, W: t.doc.defaultView || W, ox: t.ox, oy: t.oy, x: x - t.ox, y: y - t.oy,
      prim: ![...fingers.values()].some(o => o.doc === t.doc) };
    fingers.set(k, f);
    ptr(f, 'pointerover');
    const a = ptr(f, 'pointerdown');
    const b = touch(f, 'touchstart');
    f.mouse = a && b && f.prim;
    if (f.mouse) {
      mouse(f, 'mousemove'); f.md = true; mouse(f, 'mousedown');
      try { if (f.el.tabIndex >= 0 && f.el !== f.doc.body) f.el.focus({ preventScroll: true }); } catch (e) {}
    }
    ripple(x, y);
  }
  function move(k, x, y) {
    const f = fingers.get(k); if (!f) return;
    f.x = x - f.ox; f.y = y - f.oy;
    ptr(f, 'pointermove'); touch(f, 'touchmove'); if (f.mouse) mouse(f, 'mousemove');
  }
  function up(k) {
    const f = fingers.get(k); if (!f) return;
    fingers.delete(k);
    ptr(f, 'pointerup'); ptr(f, 'pointerout'); ptr(f, 'pointerleave');
    touch(f, 'touchend');
    if (f.mouse) {
      mouse(f, 'mouseup'); f.md = false;
      const t = targetAt(f.x + f.ox, f.y + f.oy);
      if (t.el === f.el || f.el.contains(t.el)) mouse(f, 'click');
    }
  }
  function releaseAll() { [...fingers.keys()].forEach(up); curKeys.clear(); }
  function ripple(x, y) {
    if (!g.cursor) return;
    const r = D.createElement('div'); r.className = 'rp'; r.style.left = x + 'px'; r.style.top = y + 'px';
    fx.appendChild(r); setTimeout(() => r.remove(), 400);
  }

  /* ---------- 配置マーク・設定モード ---------- */
  let setup = false, sel = null;
  function renderMarks() {
    marks.textContent = '';
    for (const [b, m] of Object.entries(map)) {
      if (!m || m === 'cursor') continue;
      const el = D.createElement('div');
      el.className = 'm' + (+b === sel ? ' sel' : '');
      el.textContent = nm(+b); el.dataset.b = b;
      el.style.left = m.x * innerWidth + 'px'; el.style.top = m.y * innerHeight + 'px';
      marks.appendChild(el);
    }
  }
  function renderHint() {
    const curBtns = Object.keys(map).filter(b => map[b] === 'cursor').map(b => nm(+b)).join(', ');
    if (sel == null) {
      hint.innerHTML = `コントローラーの<b>割り当てたいボタン</b>を押してね
<div class=sub>マークはドラッグで移動／タップで選択${curBtns ? '<br>カーソル位置を押すボタン: ' + curBtns : ''}</div>
<div class=row><button class=t data-a=menu>メニュー</button><button class="t p" data-a=done>完了</button></div>`;
    } else {
      const m = map[sel];
      hint.innerHTML = `<b>${nm(sel)}</b> を置く場所を<b>タップ</b>
<div class=sub>もう一度 ${nm(sel)} を押すとカーソル位置に置くよ${m === 'cursor' ? '<br>（今: カーソル位置を押すボタン）' : ''}</div>
<div class=row><button class=t data-a=cursor>カーソル位置を押すボタンにする</button>${m ? '<button class="t d" data-a=del>解除</button>' : ''}<button class=t data-a=back>戻る</button><button class="t p" data-a=done>完了</button></div>`;
    }
  }
  function place(b, x, y) {
    map[b] = { x: x / innerWidth, y: y / innerHeight }; saveMap();
    sel = null; renderMarks(); renderHint(); toast(nm(b) + ' を配置したよ');
  }
  function setSetup(v) {
    releaseAll(); setup = v; sel = null;
    root.classList.toggle('on', v); if (v) root.classList.remove('pn');
    renderMarks(); renderHint();
    if (v) idle = 0;
  }
  function setupPress(b) {
    if (DPAD.includes(b)) return;
    if (sel === b) { place(b, cur.x, cur.y); return; }
    sel = b; renderHint(); renderMarks();
  }
  hint.addEventListener('click', e => {
    const a = e.target.closest && e.target.closest('[data-a]'); if (!a) return;
    const act = a.dataset.a;
    if (act === 'done') setSetup(false);
    else if (act === 'menu') { setSetup(false); openPanel(); }
    else if (act === 'back') { sel = null; renderHint(); renderMarks(); }
    else if (act === 'cursor') { map[sel] = 'cursor'; saveMap(); toast(nm(sel) + ' はカーソル位置を押すよ'); sel = null; renderHint(); renderMarks(); }
    else if (act === 'del') { delete map[sel]; saveMap(); toast(nm(sel) + ' を解除したよ'); sel = null; renderHint(); renderMarks(); }
  });
  catcher.addEventListener('pointerdown', e => {
    e.preventDefault();
    if (sel != null) place(sel, e.clientX, e.clientY);
    else toast('先にコントローラーのボタンを押してね');
  });
  marks.addEventListener('pointerdown', e => {
    const el = e.target.closest && e.target.closest('.m');
    if (!el || !setup) return;
    e.preventDefault();
    const b = +el.dataset.b, sx = e.clientX, sy = e.clientY;
    let moved = false;
    try { el.setPointerCapture(e.pointerId); } catch (_) {}
    const mv = ev => {
      if (Math.hypot(ev.clientX - sx, ev.clientY - sy) > 6) moved = true;
      if (moved) { el.style.left = ev.clientX + 'px'; el.style.top = ev.clientY + 'px'; }
    };
    const fin = ev => {
      el.removeEventListener('pointermove', mv); el.removeEventListener('pointerup', fin); el.removeEventListener('pointercancel', fin);
      if (moved) { map[b] = { x: ev.clientX / innerWidth, y: ev.clientY / innerHeight }; saveMap(); }
      else sel = b;
      renderMarks(); renderHint();
    };
    el.addEventListener('pointermove', mv); el.addEventListener('pointerup', fin); el.addEventListener('pointercancel', fin);
  });

  /* ---------- フローティングアイコン・メニュー ---------- */
  function placeHandle() {
    hd.style.left = Math.min(innerWidth - 26, Math.max(26, g.hx * innerWidth)) + 'px';
    hd.style.top = Math.min(innerHeight - 26, Math.max(26, g.hy * innerHeight)) + 'px';
  }
  hd.addEventListener('pointerdown', e => {
    e.preventDefault();
    try { hd.setPointerCapture(e.pointerId); } catch (_) {}
    const sx = e.clientX, sy = e.clientY; let moved = false;
    const mv = ev => {
      if (Math.hypot(ev.clientX - sx, ev.clientY - sy) > 8) moved = true;
      if (moved) { g.hx = ev.clientX / innerWidth; g.hy = ev.clientY / innerHeight; placeHandle(); }
    };
    const fin = () => {
      hd.removeEventListener('pointermove', mv); hd.removeEventListener('pointerup', fin); hd.removeEventListener('pointercancel', fin);
      if (moved) saveG(); else root.classList.contains('pn') ? closePanel() : openPanel();
    };
    hd.addEventListener('pointermove', mv); hd.addEventListener('pointerup', fin); hd.addEventListener('pointercancel', fin);
  });
  let padName = '', padN = 0;
  function status() {
    st.textContent = padN ? '接続中: ' + padName : 'コントローラーのボタンを何か押すと認識されるよ';
  }
  function openPanel() {
    root.classList.remove('hh'); root.classList.add('pn'); status();
    $('cCur').checked = g.cursor; $('rSp').value = g.speed; $('cMk').checked = g.marks; $('cGd').checked = g.guard;
  }
  function closePanel() { root.classList.remove('pn'); }
  $('bSetup').onclick = () => setSetup(true);
  $('bClose').onclick = closePanel;
  $('bHide').onclick = () => { closePanel(); root.classList.add('hh'); toast('もう一度ブックマークを開くか SELECT＋START で戻せるよ'); };
  $('bRst').onclick = () => { map = { 0: 'cursor' }; saveMap(); renderMarks(); toast('このゲームの配置をリセットしたよ'); };
  $('bExit').onclick = exit;
  $('cCur').onchange = e => { g.cursor = e.target.checked; saveG(); };
  $('rSp').oninput = e => { g.speed = +e.target.value; saveG(); };
  $('cMk').onchange = e => { g.marks = e.target.checked; saveG(); root.classList.toggle('mk', g.marks); };
  $('cGd').onchange = e => { g.guard = e.target.checked; saveG(); if (g.guard) guard(); };

  /* ---------- 「戻る」ガード（Bボタン対策） ---------- */
  let guarded = false, lastPop = 0;
  function guard() {
    if (!g.guard || guarded) return;
    try { history.pushState(history.state, ''); guarded = true; } catch (e) {}
  }
  function onPop() {
    if (!g.guard) return;
    guarded = false;
    const now = Date.now();
    if (now - lastPop < 1500) { exit(); history.back(); return; }
    lastPop = now; guard(); toast('「戻る」を止めたよ（すぐもう一回で本当に戻る）');
  }
  W.addEventListener('popstate', onPop);

  /* ---------- ゲームパッド読み取りループ ---------- */
  const cur = { x: innerWidth / 2, y: innerHeight / 2 };
  const curKeys = new Set();
  let prev = [], last = performance.now(), dpHold = 0, idle = 99, raf = 0;
  const dz = v => { const a = Math.abs(v); if (a < 0.18) return 0; const t = Math.min(1, (a - 0.18) / 0.8); return Math.sign(v) * t * t; };
  function readPads() {
    const ps = [...(navigator.getGamepads ? navigator.getGamepads() : [])].filter(Boolean);
    const btn = [], ax = [0, 0, 0, 0];
    for (const p of ps) {
      p.buttons.forEach((b, i) => { if (b && (b.pressed || b.value > 0.5)) btn[i] = true; });
      for (let i = 0; i < 4; i++) { const v = p.axes[i] || 0; if (Math.abs(v) > Math.abs(ax[i])) ax[i] = v; }
    }
    if (ps.length !== padN || (ps[0] && ps[0].id !== padName)) {
      const was = padN; padN = ps.length; padName = ps[0] ? ps[0].id : ''; status();
      if (padN && !was) toast('コントローラーを認識したよ');
    }
    return { btn, ax };
  }
  function onBtn(b, p) {
    if (setup) { if (p) setupPress(b); return; }
    if (DPAD.includes(b)) return;
    const m = map[b], k = 'b' + b;
    if (p) {
      if (!m) return;
      if (m === 'cursor') { idle = 0; down(k, cur.x, cur.y); curKeys.add(k); }
      else {
        down(k, m.x * innerWidth, m.y * innerHeight);
        const el = marks.querySelector(`[data-b="${b}"]`);
        if (el) { el.classList.add('hit'); setTimeout(() => el.classList.remove('hit'), 120); }
      }
    } else { curKeys.delete(k); up(k); }
  }
  function loop(now) {
    raf = requestAnimationFrame(loop);
    const dt = Math.min(0.05, (now - last) / 1000); last = now;
    const { btn, ax } = readPads();
    if (btn[8] && btn[9] && !(prev[8] && prev[9])) {
      prev = btn; releaseAll();
      if (setup) setSetup(false); else { root.classList.remove('hh'); setSetup(true); }
      return;
    }
    const N = Math.max(btn.length, prev.length);
    for (let b = 0; b < N; b++) { const p = !!btn[b]; if (p !== !!prev[b]) onBtn(b, p); }
    prev = btn;
    const dx = (btn[15] ? 1 : 0) - (btn[14] ? 1 : 0), dy = (btn[13] ? 1 : 0) - (btn[12] ? 1 : 0);
    dpHold = dx || dy ? dpHold + dt : 0;
    const acc = Math.min(1, 0.3 + dpHold * 0.9);
    const vx = dz(ax[0]) + dz(ax[2]) * 0.3 + dx * acc, vy = dz(ax[1]) + dz(ax[3]) * 0.3 + dy * acc;
    if (vx || vy) {
      const sp = Math.min(innerWidth, innerHeight) * 1.2 * g.speed;
      cur.x = Math.max(0, Math.min(innerWidth - 1, cur.x + vx * sp * dt));
      cur.y = Math.max(0, Math.min(innerHeight - 1, cur.y + vy * sp * dt));
      for (const k of curKeys) move(k, cur.x, cur.y);
      idle = 0;
    } else idle += dt;
    curEl.style.left = cur.x + 'px'; curEl.style.top = cur.y + 'px';
    curEl.classList.toggle('v', (g.cursor || setup) && (idle < 2.5 || curKeys.size > 0 || setup));
    curEl.classList.toggle('dn', curKeys.size > 0);
  }

  /* ---------- 後片付け・イベント ---------- */
  const onResize = () => {
    cur.x = Math.min(cur.x, innerWidth - 1); cur.y = Math.min(cur.y, innerHeight - 1);
    renderMarks(); placeHandle();
  };
  const onVis = () => { if (D.hidden) releaseAll(); };
  W.addEventListener('resize', onResize);
  D.addEventListener('visibilitychange', onVis);
  D.addEventListener('fullscreenchange', mount);
  D.addEventListener('webkitfullscreenchange', mount);
  function exit() {
    cancelAnimationFrame(raf); releaseAll(); host.remove();
    W.removeEventListener('popstate', onPop); W.removeEventListener('resize', onResize);
    D.removeEventListener('visibilitychange', onVis);
    D.removeEventListener('fullscreenchange', mount); D.removeEventListener('webkitfullscreenchange', mount);
    delete W.__padtap;
  }
  W.__padtap = { open: openPanel, exit, _dbg: { map: () => map, cur, fingers } };

  root.classList.toggle('mk', g.marks);
  placeHandle(); renderMarks(); renderHint(); guard();
  raf = requestAnimationFrame(loop);
  const hasMarks = Object.values(map).some(m => m && m !== 'cursor');
  toast(hasMarks ? 'PadTap 起動。このゲームの配置を読み込んだよ' : 'PadTap 起動。🎮 からボタン配置を設定してね');
})();
