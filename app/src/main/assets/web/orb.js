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
  function resize() { DPR = Math.min(devicePixelRatio || 1, 2); W = innerWidth; H = innerHeight; bg.width = W * DPR; bg.height = H * DPR; sizeOrb(); }
  addEventListener('resize', resize);

  function drawBg(t) {
    bctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    const g = bctx.createRadialGradient(W * .5, H * .3, 0, W * .5, H * .35, Math.max(W, H) * .8);
    g.addColorStop(0, S.persona === 'ultron' ? '#1a0408' : S.persona === 'nova' ? '#1a1205' : '#061a2c');
    g.addColorStop(.55, S.persona === 'ultron' ? '#080203' : S.persona === 'nova' ? '#080602' : '#030b14');
    g.addColorStop(1, '#010306');
    bctx.fillStyle = g; bctx.fillRect(0, 0, W, H);
    // ince çapraz ışık çizgileri
    bctx.save(); bctx.globalAlpha = .07; bctx.strokeStyle = S.col.c; bctx.lineWidth = 1;
    for (let i = 0; i < 6; i++) {
      const x = (i / 6) * W * 1.4 - W * .2 + Math.sin(t * .05 + i) * 10;
      bctx.beginPath(); bctx.moveTo(x, 0); bctx.lineTo(x - H * .35, H); bctx.stroke();
    }
    bctx.restore();
    // ızgara noktaları
    bctx.fillStyle = A(.06);
    for (let y = 20; y < H; y += 34) for (let x = (y / 34 % 2) * 17; x < W; x += 34) bctx.fillRect(x, y, 1.2, 1.2);
    // parçacıklar
    for (const p of parts) {
      p.y -= p.s * 16 * (S.state === 'thinking' ? 3 : 1); if (p.y < -.02) { p.y = 1.02; p.x = Math.random(); }
      bctx.fillStyle = A(.18 + .25 * (.5 + .5 * Math.sin(t * 1.7 + p.p)));
      bctx.beginPath(); bctx.arc(p.x * W, p.y * H, p.r, 0, TAU); bctx.fill();
    }
  }

  // ---------------------------------------------------------------- orb
  const oc = document.getElementById('orb');
  const octx = oc.getContext('2d');
  let OW = 0;
  function sizeOrb() { const r = oc.getBoundingClientRect(); OW = r.width; oc.width = r.width * DPR; oc.height = r.height * DPR; }

  function arc(ctx, cx, cy, r, a0, a1, w, color, blur) {
    ctx.save(); ctx.beginPath(); ctx.arc(cx, cy, r, a0, a1); ctx.lineWidth = w; ctx.strokeStyle = color;
    if (blur) { ctx.shadowColor = S.col.c; ctx.shadowBlur = blur; }
    ctx.stroke(); ctx.restore();
  }

  function drawOrb(ctx, cx, cy, R, t, st, L, persona) {
    const energy = S.energy;
    const spd = st === 'thinking' ? 1.4 : st === 'connecting' ? .9 : st === 'speaking' ? .35 : .08;
    const breathe = .5 + .5 * Math.sin(t * 1.4);
    const glow = 10 + (st === 'idle' ? breathe * 8 : 0) + L * 26 + energy * 18;
    const pulse = 1 + (st === 'speaking' ? L * .05 : st === 'listening' ? L * .03 : .006 * breathe);
    ctx.save(); ctx.translate(cx, cy); ctx.scale(pulse, pulse); ctx.translate(-cx, -cy);

    // dışa yayılan ışık halkaları (konuşurken)
    for (const rp of S.ripples) { arc(ctx, cx, cy, R * rp.r, 0, TAU, 2, A(rp.a), 0); }

    // dış ince halka + nişan çizgileri
    arc(ctx, cx, cy, R * 1.0, 0, TAU, 1, A(.35), 0);
    for (let k = 0; k < 4; k++) {
      const a = k * Math.PI / 2 - Math.PI / 2;
      ctx.beginPath(); ctx.moveTo(cx + Math.cos(a) * R * 1.02, cy + Math.sin(a) * R * 1.02); ctx.lineTo(cx + Math.cos(a) * R * 1.12, cy + Math.sin(a) * R * 1.12);
      ctx.strokeStyle = A(.7); ctx.lineWidth = 1.5; ctx.stroke();
    }
    // dış ince yaylar (mockup'taki ince parlak kavisler)
    arc(ctx, cx, cy, R * 1.06, -2.5 + t * spd * .3, -1.7 + t * spd * .3, 1.2, A(.6), 6);
    arc(ctx, cx, cy, R * 1.06, .7 + t * spd * .3, 1.5 + t * spd * .3, 1.2, A(.6), 6);

    // noktalı çember
    ctx.save(); ctx.setLineDash([1.5, 5]); arc(ctx, cx, cy, R * .93, 0, TAU, 1.5, A(.45), 0); ctx.restore();

    // ana kalın segmentler
    const rot = t * spd * .5;
    const segs = persona === 'ultron'
      ? [[-2.05, -1.1], [-.45, .45], [1.1, 2.05], [2.7, 3.6]]
      : [[-2.15, -1.0], [-.32, .32], [1.0, 2.15], [2.82, 3.46]];
    const segW = R * (.075 + L * .03 + energy * .02);
    for (const [a0, a1] of segs) {
      if (persona === 'ultron') {
        // açılı, kesik parçalar
        const n = 5, step = (a1 - a0) / n;
        for (let i = 0; i < n; i++) arc(ctx, cx, cy, R * .8, rot + a0 + i * step + .03, rot + a0 + (i + 1) * step - .03, segW, A(.95), glow);
      } else {
        arc(ctx, cx, cy, R * .8, rot + a0, rot + a1, segW, A(.95), glow);
      }
      arc(ctx, cx, cy, R * .8, rot + a0, rot + a1, segW * .35, S.col.hot, 0);
    }
    // segmentlerin üstünde ince ışık
    arc(ctx, cx, cy, R * .88, 0, TAU, 1, A(.25), 0);

    // tik halkası (ters döner)
    ctx.save(); ctx.translate(cx, cy); ctx.rotate(-t * spd * .7);
    const ticks = 90;
    for (let i = 0; i < ticks; i++) {
      const a = (i / ticks) * TAU, long = i % 5 === 0;
      const r0 = R * .64, r1 = R * (long ? .705 : .68);
      ctx.beginPath(); ctx.moveTo(Math.cos(a) * r0, Math.sin(a) * r0); ctx.lineTo(Math.cos(a) * r1, Math.sin(a) * r1);
      ctx.strokeStyle = A(long ? .6 : .3); ctx.lineWidth = long ? 1.4 : 1; ctx.stroke();
    }
    ctx.restore();

    // dinlerken/konuşurken iç dalga halkası
    if (st === 'listening' || st === 'speaking') {
      const n = 120, amp = R * (.012 + L * (st === 'speaking' ? .07 : .05));
      ctx.beginPath();
      for (let i = 0; i <= n; i++) {
        const a = (i / n) * TAU;
        let v = Math.sin(a * 6 + t * 5) * .5 + Math.sin(a * 11 - t * 7) * .3 + Math.sin(a * 17 + t * 9) * .2;
        if (persona === 'ultron') v = Math.sign(v) * Math.pow(Math.abs(v), .5);
        const rr = R * .6 + v * amp;
        const x = cx + Math.cos(a) * rr, y = cy + Math.sin(a) * rr;
        i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
      }
      ctx.closePath(); ctx.lineWidth = 1.6; ctx.strokeStyle = A(.85); ctx.shadowColor = S.col.c; ctx.shadowBlur = 8; ctx.stroke(); ctx.shadowBlur = 0;
    }

    // iç çift halka
    arc(ctx, cx, cy, R * .56, 0, TAU, 2.2, A(.9), 12 + L * 14);
    arc(ctx, cx, cy, R * .52, 0, TAU, 1, A(.4), 0);

    // düşünme: enerji taraması + içe akan parçacıklar
    if (st === 'thinking' || st === 'connecting') {
      const a = t * (st === 'thinking' ? 4.2 : 2.4);
      arc(ctx, cx, cy, R * .73, a, a + 1.1, 3, S.col.hot, 14);
      arc(ctx, cx, cy, R * .73, a + Math.PI, a + Math.PI + 1.1, 3, S.col.hot, 14);
      for (let i = 0; i < 14; i++) {
        const ph = ((t * .9 + i / 14) % 1);
        const aa = i * 2.4 + t * .6, rr = R * (.95 - ph * .45);
        ctx.fillStyle = A(.9 * ph); ctx.beginPath(); ctx.arc(cx + Math.cos(aa) * rr, cy + Math.sin(aa) * rr, 1.6, 0, TAU); ctx.fill();
      }
    }

    // nova: yörüngede dönen ışık noktaları
    if (persona === 'nova') {
      for (let i = 0; i < 3; i++) {
        const a = t * (.6 + i * .25) * (st === 'thinking' ? 3 : 1) + i * 2.1;
        const x = cx + Math.cos(a) * R * .93, y = cy + Math.sin(a) * R * .93;
        ctx.beginPath(); ctx.arc(x, y, 3.2 - i * .6, 0, TAU); ctx.fillStyle = S.col.hot; ctx.shadowColor = S.col.c; ctx.shadowBlur = 12; ctx.fill(); ctx.shadowBlur = 0;
      }
    }
    // ultron: dönen üçgen
    if (persona === 'ultron') {
      ctx.save(); ctx.translate(cx, cy); ctx.rotate(-t * .2);
      ctx.beginPath();
      for (let i = 0; i <= 3; i++) { const a = (i / 3) * TAU - Math.PI / 2; const x = Math.cos(a) * R * .48, y = Math.sin(a) * R * .48; i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); }
      ctx.strokeStyle = A(.35); ctx.lineWidth = 1; ctx.stroke(); ctx.restore();
    }

    // merkez disk
    const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, R * .5);
    g.addColorStop(0, A(.22 + L * .25 + energy * .2)); g.addColorStop(.6, 'rgba(2,8,14,.92)'); g.addColorStop(1, 'rgba(2,8,14,.6)');
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(cx, cy, R * .5, 0, TAU); ctx.fill();
    ctx.restore();
  }

  function frame(now) {
    const t = (now - S.t0) / 1000;
    S.level += (S.target - S.level) * .3;
    S.target *= .9;
    const wantE = S.state === 'thinking' ? 1 : S.state === 'speaking' ? .5 : 0;
    S.energy += (wantE - S.energy) * .05;
    // dalgalar
    if (S.state === 'speaking' && S.level > .28 && now - S.lastRipple > 260) { S.ripples.push({ r: .8, a: .55 }); S.lastRipple = now; }
    S.ripples.forEach((r) => { r.r += .012; r.a *= .955; });
    S.ripples = S.ripples.filter((r) => r.a > .03 && r.r < 1.35);

    drawBg(t);
    if (OW > 0 && document.body.dataset.v === 'home') {
      octx.setTransform(DPR, 0, 0, DPR, 0, 0);
      octx.clearRect(0, 0, OW, OW);
      drawOrb(octx, OW / 2, OW / 2, OW / 2 * .74, t, S.state, S.level, S.persona);
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
    setPersona(p) { S.persona = p; readColors(); },
    drawOrb(ctx, cx, cy, R, t, st, L, p) { drawOrb(ctx, cx, cy, R, t, st, L, p || S.persona); },
    drawWeatherIcon, resize: sizeOrb,
  };
  readColors(); resize(); requestAnimationFrame(frame);
})();
