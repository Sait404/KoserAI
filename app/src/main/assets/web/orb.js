/* KoserAI orb + arka plan. Durumlar: idle, connecting, listening, thinking, speaking.
   Konuşma animasyonu gerçek ses seviyesine (telefondan gelen) bağlıdır. */
(function () {
  const TAU = Math.PI * 2;
  const S = { state: 'idle', level: 0, target: 0, persona: 'koser', t0: performance.now(), energy: 0, ripples: [], lastRipple: 0, col: null };

  function readColors() {
    const cs = getComputedStyle(document.body);
    const rgb = cs.getPropertyValue('--rgb').trim() || '47,208,255';
    const hex = (v) => cs.getPropertyValue(v).trim();
    S.col = { rgb, c: hex('--c'), c2: hex('--c2'), hot: hex('--hot') };
  }
  const A = (o) => `rgba(${S.col.rgb},${o})`;

  // ---------------------------------------------------------------- arka plan
  const bg = document.getElementById('bg');
  const bctx = bg.getContext('2d');
  let W = 0, H = 0, DPR = 1;
  const parts = Array.from({ length: 40 }, () => ({ x: Math.random(), y: Math.random(), r: Math.random() * 1.3 + .3, s: Math.random() * .00018 + .00004, p: Math.random() * TAU }));
  function resize() { DPR = Math.min(devicePixelRatio || 1, 1.3); W = innerWidth; H = innerHeight; bg.width = W * DPR; bg.height = H * DPR; sizeOrb(); bgDirty = true; }
  let bgDirty = true;
  addEventListener('resize', resize);

  function drawBg(t) {
    bctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    bctx.fillStyle = '#010204'; bctx.fillRect(0, 0, W, H);
    const g = bctx.createRadialGradient(W * .5, H * .45, 0, W * .5, H * .45, Math.max(W, H) * .7);
    g.addColorStop(0, A(.07)); g.addColorStop(.5, A(.02)); g.addColorStop(1, 'rgba(0,0,0,0)');
    bctx.fillStyle = g; bctx.fillRect(0, 0, W, H);
    // çok hafif ızgara
    bctx.strokeStyle = A(.035); bctx.lineWidth = 1; bctx.beginPath();
    for (let x = (W % 40) / 2; x < W; x += 40) { bctx.moveTo(x, 0); bctx.lineTo(x, H); }
    for (let y = 0; y < H; y += 40) { bctx.moveTo(0, y); bctx.lineTo(W, y); }
    bctx.stroke();
  }

  // ---------------------------------------------------------------- orb
  const oc = document.getElementById('orb');
  const octx = oc.getContext('2d');
  let OW = 0, OH = 0;
  function sizeOrb() { const r = oc.getBoundingClientRect(); OW = r.width; OH = r.height; oc.width = r.width * DPR; oc.height = r.height * DPR; }

  function arc(ctx, cx, cy, r, a0, a1, w, color, blur) {
    ctx.save(); ctx.beginPath(); ctx.arc(cx, cy, r, a0, a1); ctx.lineWidth = w; ctx.strokeStyle = color;
    if (blur) { ctx.shadowColor = S.col.c; ctx.shadowBlur = blur; }
    ctx.stroke(); ctx.restore();
  }

  // dönen ince yaylar (her birinin kendi yarıçapı, eğimi, hızı)
  const ARCS = Array.from({ length: 9 }, (_, i) => ({
    r: .42 + i * .07 + Math.random() * .03, len: .6 + Math.random() * 2.2, sp: (Math.random() * .5 + .15) * (i % 2 ? 1 : -1),
    tilt: (Math.random() - .5) * 1.4, squash: .55 + Math.random() * .45, ph: Math.random() * TAU, w: .8 + Math.random() * 1.6,
  }));
  const DUST = Array.from({ length: 18 }, () => ({ r: .5 + Math.random() * .7, a: Math.random() * TAU, sp: (Math.random() * .3 + .05) * (Math.random() < .5 ? -1 : 1), s: Math.random() * 1.4 + .4 }));

  function drawOrb(ctx, cx, cy, R, t, st, L, persona) {
    const E = S.energy;
    const speed = st === 'thinking' ? 4 : st === 'connecting' ? 2.2 : st === 'speaking' ? 1.4 + L : st === 'listening' ? 1 + L * .8 : .45;
    const breathe = .5 + .5 * Math.sin(t * 1.3);
    // ortam ışığı (sahneyi aydınlatan geniş, soluk hale)
    const amb = ctx.createRadialGradient(cx, cy, 0, cx, cy, R * 3.2);
    amb.addColorStop(0, A(.12 + L * .12 + E * .06)); amb.addColorStop(.35, A(.04)); amb.addColorStop(1, A(0));
    ctx.fillStyle = amb; ctx.fillRect(cx - R * 3.2, cy - R * 3.2, R * 6.4, R * 6.4);

    // konuşurken dışa yayılan halkalar
    for (const rp of S.ripples) { ctx.beginPath(); ctx.arc(cx, cy, R * rp.r, 0, TAU); ctx.lineWidth = 1.2; ctx.strokeStyle = A(rp.a * .7); ctx.stroke(); }

    // dönen yaylar
    ctx.save(); ctx.lineCap = 'round';
    ARCS.forEach((a, i) => {
      const pull = st === 'thinking' ? .82 + .1 * Math.sin(t * 3 + i) : 1;
      const rr = R * a.r * pull * (1 + (st === 'speaking' ? L * .12 : st === 'listening' ? L * .07 : 0));
      const start = a.ph + t * a.sp * speed;
      const len = a.len * (st === 'idle' ? .8 + .2 * breathe : 1) + (st === 'speaking' ? L * .8 : 0);
      ctx.save(); ctx.translate(cx, cy); ctx.rotate(a.tilt); ctx.scale(1, a.squash);
      ctx.beginPath(); ctx.arc(0, 0, rr, start, start + len);
      ctx.restore();
      const alpha = (.18 + .5 * (1 - i / ARCS.length)) * (st === 'idle' ? .55 + .3 * breathe : .85) + L * .3;
      ctx.lineWidth = a.w * (1 + L * 1.2 + E * .6);
      ctx.strokeStyle = A(Math.min(1, alpha));
      if (persona === 'ultron') ctx.setLineDash([R * .05, R * .03]); else ctx.setLineDash([]);
      ctx.stroke();
      ctx.lineWidth = a.w * (3.5 + L * 3); ctx.strokeStyle = A(Math.min(1, alpha) * .18); ctx.stroke();
      if (persona === 'nova') { // yay ucunda parlayan nokta
        const ex = Math.cos(start + len) * rr, ey = Math.sin(start + len) * rr * a.squash;
        const cs = Math.cos(a.tilt), sn = Math.sin(a.tilt);
        ctx.beginPath(); ctx.arc(cx + ex * cs - ey * sn, cy + ex * sn + ey * cs, 2 + L * 2, 0, TAU); ctx.fillStyle = S.col.hot; ctx.fill();
      }
    });
    ctx.setLineDash([]); ctx.restore();

    // toz parçacıkları
    for (const d of DUST) {
      d.a += d.sp * .01 * speed;
      const rr = R * d.r * (st === 'thinking' ? .75 : 1);
      ctx.fillStyle = A(.25 + .35 * Math.abs(Math.sin(t + d.a)));
      ctx.beginPath(); ctx.arc(cx + Math.cos(d.a) * rr * 1.25, cy + Math.sin(d.a) * rr * .9, d.s, 0, TAU); ctx.fill();
    }

    // çekirdek: parlayan küre
    const flick = 1 + (Math.random() - .5) * .03 * (st === 'idle' ? .3 : 1);
    const core = R * (.11 + L * .1 + E * .03 + breathe * .01) * flick;
    const halo = ctx.createRadialGradient(cx, cy, 0, cx, cy, core * 6);
    halo.addColorStop(0, A(.7 + L * .3)); halo.addColorStop(.18, A(.3 + L * .2)); halo.addColorStop(.5, A(.07)); halo.addColorStop(1, A(0));
    ctx.fillStyle = halo; ctx.beginPath(); ctx.arc(cx, cy, core * 6, 0, TAU); ctx.fill();
    const g = ctx.createRadialGradient(cx - core * .25, cy - core * .25, 0, cx, cy, core);
    g.addColorStop(0, '#ffffff'); g.addColorStop(.35, S.col.hot); g.addColorStop(.75, S.col.c2); g.addColorStop(1, A(.0));
    ctx.fillStyle = g;
    ctx.beginPath(); ctx.arc(cx, cy, core, 0, TAU); ctx.fill();
    // ultron: merkezde dikey "göz" yarığı
    if (persona === 'ultron') { ctx.fillStyle = 'rgba(40,0,4,.55)'; ctx.fillRect(cx - core * .08, cy - core * .7, core * .16, core * 1.4); }
  }

  let lastF = 0;
  function frame(now) {
    if (document.hidden) { requestAnimationFrame(frame); return; }
    if (S.state === 'idle' && now - lastF < 32) { requestAnimationFrame(frame); return; }
    lastF = now;
    const t = (now - S.t0) / 1000;
    S.level += (S.target - S.level) * .3;
    S.target *= .9;
    const wantE = S.state === 'thinking' ? 1 : S.state === 'speaking' ? .5 : 0;
    S.energy += (wantE - S.energy) * .05;
    // dalgalar
    if (S.state === 'speaking' && S.level > .3 && now - S.lastRipple > 320) { S.ripples.push({ r: .35, a: .5 }); S.lastRipple = now; }
    S.ripples.forEach((r) => { r.r += .014; r.a *= .955; });
    S.ripples = S.ripples.filter((r) => r.a > .03 && r.r < 1.5);

    if (bgDirty) { drawBg(t); bgDirty = false; }
    if (OW > 0 && document.body.dataset.v === 'home') {
      octx.setTransform(DPR, 0, 0, DPR, 0, 0);
      octx.clearRect(0, 0, OW, OH);
      drawOrb(octx, OW / 2, OH / 2, Math.min(OW, OH) / 2 * .78, t, S.state, S.level, S.persona);
    }
    requestAnimationFrame(frame);
  }

  // ---------------------------------------------------------------- hava simgesi
  function drawWeatherIcon(cv, code, isDay) {
    const c = cv.getContext('2d'); const w = cv.width, h = cv.height;
    c.clearRect(0, 0, w, h);
    const s = w / 76;
    c.save(); c.scale(s, s);
    const rainy = code >= 51 && code <= 82, snowy = (code >= 71 && code <= 77) || code === 85 || code === 86, storm = code >= 95;
    const cloudy = code >= 2;
    if (!cloudy || code === 2) {
      c.save(); c.translate(code === 2 ? 50 : 38, code === 2 ? 22 : 34);
      if (isDay) {
        c.fillStyle = '#ffd36a'; c.shadowColor = '#ffc24a'; c.shadowBlur = 10; c.beginPath(); c.arc(0, 0, code === 2 ? 11 : 16, 0, TAU); c.fill();
      } else {
        c.fillStyle = S.col.c2; c.shadowColor = S.col.c; c.shadowBlur = 10;
        c.beginPath(); c.arc(0, 0, code === 2 ? 11 : 15, 0, TAU); c.fill();
        c.globalCompositeOperation = 'destination-out'; c.beginPath(); c.arc(6, -5, code === 2 ? 9 : 12, 0, TAU); c.fill();
      }
      c.restore();
    }
    if (cloudy) {
      c.fillStyle = S.col.c2; c.shadowColor = S.col.c; c.shadowBlur = 8;
      c.beginPath(); c.arc(28, 44, 12, 0, TAU); c.arc(42, 38, 15, 0, TAU); c.arc(55, 46, 10, 0, TAU); c.rect(20, 44, 38, 12); c.fill();
      c.shadowBlur = 0;
    }
    if (rainy || storm) { c.strokeStyle = S.col.c; c.lineWidth = 2.4; c.lineCap = 'round'; for (const x of [28, 40, 52]) { c.beginPath(); c.moveTo(x, 62); c.lineTo(x - 4, 70); c.stroke(); } }
    if (snowy) { c.fillStyle = '#fff'; for (const x of [28, 40, 52]) { c.beginPath(); c.arc(x, 66, 2.2, 0, TAU); c.fill(); } }
    c.restore();
  }

  window.ORB = {
    setState(s) { S.state = s; },
    setLevel(v) { S.target = Math.max(S.target, v); },
    setPersona(p) { S.persona = p; readColors(); bgDirty = true; },
    drawOrb(ctx, cx, cy, R, t, st, L, p) { drawOrb(ctx, cx, cy, R, t, st, L, p || S.persona); },
    drawWeatherIcon, resize: sizeOrb,
  };
  readColors(); resize(); requestAnimationFrame(frame);
})();
