/* KoserAI HUD çizimi — üç tema: koser (mavi reaktör), kizil (kırmızı, agresif), altin (zarif) */
(function () {
  const TAU = Math.PI * 2;
  const PAL = {
    koser: { c: [53, 224, 255], c2: [154, 243, 255], hot: [232, 252, 255], bg: [3, 7, 12], bg2: [7, 25, 40] },
    kizil: { c: [255, 42, 58], c2: [255, 122, 99], hot: [255, 225, 220], bg: [7, 2, 3], bg2: [34, 5, 8] },
    altin: { c: [255, 194, 74], c2: [255, 227, 161], hot: [255, 246, 222], bg: [8, 6, 3], bg2: [32, 22, 8] },
  };
  const rgba = (a, o) => `rgba(${a[0]},${a[1]},${a[2]},${o})`;

  const H = {
    theme: 'koser', state: 'idle', level: 0, target: 0, t0: performance.now(),
    flash: 0, // tema değişiminde parlama
  };

  // ---- basit gürültü (dalga halkası için)
  function noise(a, t, k) {
    return Math.sin(a * 3 + t * 2.1) * 0.5 + Math.sin(a * 7 - t * 3.3 + k) * 0.3 + Math.sin(a * 13 + t * 5.7) * 0.2;
  }

  const particles = Array.from({ length: 46 }, () => ({
    x: Math.random(), y: Math.random(), r: Math.random() * 1.6 + 0.3, s: Math.random() * 0.00025 + 0.00005, p: Math.random() * TAU,
  }));

  function drawBackground(ctx, w, h, cx, cy, t, P) {
    const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, Math.max(w, h) * 0.75);
    g.addColorStop(0, rgba(P.bg2, 1));
    g.addColorStop(1, rgba(P.bg, 1));
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, w, h);

    ctx.save();
    if (H.theme === 'koser') {
      // altıgen nokta ızgarası
      const s = 26;
      ctx.fillStyle = rgba(P.c, 0.09);
      for (let y = 0, row = 0; y < h + s; y += s * 0.866, row++) {
        for (let x = (row % 2) * s / 2; x < w + s; x += s) {
          ctx.fillRect(x, y, 1.4, 1.4);
        }
      }
    } else if (H.theme === 'kizil') {
      ctx.strokeStyle = rgba(P.c, 0.06);
      ctx.lineWidth = 1;
      const off = (t * 18) % 34;
      ctx.beginPath();
      for (let x = -h; x < w + h; x += 34) { ctx.moveTo(x + off, 0); ctx.lineTo(x + off + h * 0.6, h); }
      ctx.stroke();
    } else {
      ctx.strokeStyle = rgba(P.c, 0.05);
      ctx.lineWidth = 1;
      for (let r = 40; r < Math.max(w, h); r += 46) { ctx.beginPath(); ctx.arc(cx, cy, r, 0, TAU); ctx.stroke(); }
    }
    // parçacıklar
    for (const p of particles) {
      p.y -= p.s * (H.theme === 'kizil' ? 2.2 : 1) * 16;
      if (p.y < -0.02) { p.y = 1.02; p.x = Math.random(); }
      const tw = 0.35 + 0.35 * Math.sin(t * 2 + p.p);
      ctx.fillStyle = rgba(P.c2, tw * 0.5);
      ctx.beginPath(); ctx.arc(p.x * w, p.y * h, p.r, 0, TAU); ctx.fill();
    }
    ctx.restore();
  }

  function ring(ctx, cx, cy, r, w, col, a0 = 0, a1 = TAU) {
    ctx.beginPath(); ctx.arc(cx, cy, r, a0, a1); ctx.lineWidth = w; ctx.strokeStyle = col; ctx.stroke();
  }

  function waveRing(ctx, cx, cy, r, amp, t, col, width, sharp) {
    const n = 120;
    ctx.beginPath();
    for (let i = 0; i <= n; i++) {
      const a = (i / n) * TAU;
      let v = noise(a, t, 1.3);
      if (sharp) v = Math.sign(v) * Math.pow(Math.abs(v), 0.45) * (i % 2 ? 1 : 0.35);
      const rr = r + v * amp;
      const x = cx + Math.cos(a) * rr, y = cy + Math.sin(a) * rr;
      i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
    }
    ctx.closePath(); ctx.lineWidth = width; ctx.strokeStyle = col; ctx.stroke();
  }

  function core(ctx, cx, cy, r, P, a) {
    const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, r);
    g.addColorStop(0, rgba(P.hot, a));
    g.addColorStop(0.25, rgba(P.c2, a * 0.9));
    g.addColorStop(0.55, rgba(P.c, a * 0.45));
    g.addColorStop(1, rgba(P.c, 0));
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(cx, cy, r, 0, TAU); ctx.fill();
  }

  // ======================================================== KOSER
  function orbKoser(ctx, cx, cy, R, t, L, st, P) {
    const busy = st === 'thinking';
    // dış ölçek halkası
    ctx.save(); ctx.translate(cx, cy); ctx.rotate(t * 0.05);
    for (let i = 0; i < 72; i++) {
      const a = (i / 72) * TAU, long = i % 6 === 0;
      const r0 = R * 1.27, r1 = R * (long ? 1.36 : 1.31);
      ctx.beginPath(); ctx.moveTo(Math.cos(a) * r0, Math.sin(a) * r0); ctx.lineTo(Math.cos(a) * r1, Math.sin(a) * r1);
      ctx.lineWidth = long ? 2 : 1; ctx.strokeStyle = rgba(P.c, long ? 0.7 : 0.35); ctx.stroke();
    }
    ctx.restore();
    ring(ctx, cx, cy, R * 1.22, 1, rgba(P.c, 0.25));

    // parçalı ana halka
    const rot = t * (busy ? 1.6 : 0.35);
    for (let k = 0; k < 3; k++) {
      const a = rot + (k * TAU) / 3;
      ring(ctx, cx, cy, R * 1.05, 4, rgba(P.c, 0.85), a, a + TAU / 3 - 0.32);
    }
    // ters dönen kesikli halka
    ctx.save(); ctx.setLineDash([R * 0.05, R * 0.07]); ctx.lineDashOffset = -t * 30;
    ring(ctx, cx, cy, R * 0.9, 2, rgba(P.c2, 0.55)); ctx.restore();

    // reaktör bobinleri
    const coils = 10;
    for (let i = 0; i < coils; i++) {
      const a = (i / coils) * TAU - t * 0.2;
      const pulse = 0.45 + 0.55 * Math.max(0, Math.sin(t * 3 - i * 0.6)) * (st === 'idle' ? 0.5 : 1);
      ctx.save(); ctx.translate(cx, cy); ctx.rotate(a);
      ctx.beginPath();
      const r0 = R * 0.52, r1 = R * 0.72, wv = 0.19;
      ctx.moveTo(Math.cos(-wv) * r0, Math.sin(-wv) * r0);
      ctx.lineTo(Math.cos(-wv * 0.75) * r1, Math.sin(-wv * 0.75) * r1);
      ctx.lineTo(Math.cos(wv * 0.75) * r1, Math.sin(wv * 0.75) * r1);
      ctx.lineTo(Math.cos(wv) * r0, Math.sin(wv) * r0);
      ctx.closePath();
      ctx.fillStyle = rgba(P.c, 0.16 + pulse * 0.32); ctx.fill();
      ctx.strokeStyle = rgba(P.c2, 0.5); ctx.lineWidth = 1; ctx.stroke();
      ctx.restore();
    }
    ring(ctx, cx, cy, R * 0.5, 1.5, rgba(P.c2, 0.7));

    // ses dalgası
    if (st === 'listening' || st === 'speaking') {
      waveRing(ctx, cx, cy, R * 0.8, R * (0.04 + L * 0.22), t * (st === 'speaking' ? 1.6 : 1), rgba(P.hot, 0.85), 2, false);
      waveRing(ctx, cx, cy, R * 0.8, R * (0.02 + L * 0.14), -t * 1.3, rgba(P.c, 0.5), 1, false);
    }
    // düşünme taraması
    if (busy) {
      const a = t * 4;
      ring(ctx, cx, cy, R * 1.14, 3, rgba(P.hot, 0.9), a, a + 1.0);
      ring(ctx, cx, cy, R * 1.14, 3, rgba(P.hot, 0.9), a + Math.PI, a + Math.PI + 1.0);
    }
    const breathe = st === 'idle' ? 0.08 * Math.sin(t * 1.6) : 0;
    core(ctx, cx, cy, R * (0.48 + L * 0.18 + breathe), P, 0.95);
    ctx.beginPath(); ctx.arc(cx, cy, R * 0.12, 0, TAU); ctx.fillStyle = rgba(P.hot, 0.95); ctx.fill();
  }

  // ======================================================== KIZIL
  function orbKizil(ctx, cx, cy, R, t, L, st, P) {
    const jit = st === 'idle' ? 0 : (Math.random() - 0.5) * R * 0.012 * (1 + L * 3);
    cx += jit; cy += jit * 0.5;
    // altıgram (dönen iki üçgen)
    for (let k = 0; k < 2; k++) {
      const a0 = (k ? -1 : 1) * t * 0.25 + k * Math.PI;
      ctx.beginPath();
      for (let i = 0; i <= 3; i++) {
        const a = a0 + (i / 3) * TAU - Math.PI / 2;
        const x = cx + Math.cos(a) * R * 1.32, y = cy + Math.sin(a) * R * 1.32;
        i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
      }
      ctx.lineWidth = 1.3; ctx.strokeStyle = rgba(P.c, 0.45); ctx.stroke();
    }
    // dikenli halka
    const n = 56, rot = t * (st === 'thinking' ? 1.4 : 0.3);
    ctx.beginPath();
    for (let i = 0; i <= n; i++) {
      const a = rot + (i / n) * TAU;
      const sp = i % 2 ? 1.0 + 0.06 + L * 0.16 * (0.5 + 0.5 * Math.sin(i * 1.7 + t * 9)) : 0.96;
      const x = cx + Math.cos(a) * R * sp, y = cy + Math.sin(a) * R * sp;
      i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
    }
    ctx.closePath(); ctx.lineWidth = 2; ctx.strokeStyle = rgba(P.c, 0.9); ctx.shadowColor = rgba(P.c, 0.9); ctx.shadowBlur = 12; ctx.stroke(); ctx.shadowBlur = 0;

    // iç altıgen parçaları
    for (let i = 0; i < 6; i++) {
      const a = -t * 0.6 + (i / 6) * TAU;
      const b = a + TAU / 6 - 0.18;
      ctx.beginPath();
      ctx.moveTo(cx + Math.cos(a) * R * 0.74, cy + Math.sin(a) * R * 0.74);
      ctx.lineTo(cx + Math.cos(b) * R * 0.74, cy + Math.sin(b) * R * 0.74);
      ctx.lineWidth = 5; ctx.strokeStyle = rgba(P.c, (st === 'thinking' && (Math.floor(t * 8) % 6) === i) ? 1 : 0.5); ctx.stroke();
    }
    if (st === 'listening' || st === 'speaking') {
      waveRing(ctx, cx, cy, R * 0.86, R * (0.05 + L * 0.3), t * 2.2, rgba(P.hot, 0.8), 1.6, true);
    }
    // göz
    const eyeW = R * (0.62 + L * 0.2), eyeH = R * (0.16 + L * 0.1) * (st === 'idle' ? (0.75 + 0.25 * Math.abs(Math.sin(t * 0.9))) : 1);
    const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, eyeW);
    g.addColorStop(0, rgba(P.hot, 1)); g.addColorStop(0.18, rgba(P.c2, 0.95)); g.addColorStop(0.5, rgba(P.c, 0.55)); g.addColorStop(1, rgba(P.c, 0));
    ctx.save(); ctx.translate(cx, cy); ctx.scale(1, eyeH / eyeW);
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(0, 0, eyeW, 0, TAU); ctx.fill(); ctx.restore();
    ctx.fillStyle = rgba(P.hot, 1); ctx.fillRect(cx - R * 0.2, cy - 1.5, R * 0.4, 3);
    core(ctx, cx, cy, R * 0.5, P, 0.35);
  }

  // ======================================================== ALTIN
  function orbAltin(ctx, cx, cy, R, t, L, st, P) {
    const busy = st === 'thinking';
    // ışınlar
    const rays = 60;
    for (let i = 0; i < rays; i++) {
      const a = (i / rays) * TAU + t * 0.03;
      const len = R * (0.1 + 0.08 * (0.5 + 0.5 * Math.sin(i * 0.9 + t * 1.3)) + L * 0.12);
      ctx.beginPath();
      ctx.moveTo(cx + Math.cos(a) * R * 1.12, cy + Math.sin(a) * R * 1.12);
      ctx.lineTo(cx + Math.cos(a) * (R * 1.12 + len), cy + Math.sin(a) * (R * 1.12 + len));
      ctx.lineWidth = i % 5 === 0 ? 1.8 : 0.8; ctx.strokeStyle = rgba(P.c, i % 5 === 0 ? 0.7 : 0.35); ctx.stroke();
    }
    // yörüngeler
    const orbits = [[1.0, 0.4, 3], [0.8, -0.65, 2], [0.62, 1.0, 4]];
    orbits.forEach(([rr, sp, dots], k) => {
      ring(ctx, cx, cy, R * rr, 1, rgba(P.c, 0.4));
      for (let d = 0; d < dots; d++) {
        const a = t * sp * (busy ? 3 : 1) + (d / dots) * TAU + k;
        const x = cx + Math.cos(a) * R * rr, y = cy + Math.sin(a) * R * rr;
        ctx.beginPath(); ctx.arc(x, y, d === 0 ? 4 : 2.4, 0, TAU); ctx.fillStyle = rgba(P.hot, 0.9);
        ctx.shadowColor = rgba(P.c, 1); ctx.shadowBlur = 10; ctx.fill(); ctx.shadowBlur = 0;
      }
    });
    // elmaslar
    for (let i = 0; i < 12; i++) {
      const a = (i / 12) * TAU - t * 0.15;
      const x = cx + Math.cos(a) * R * 0.46, y = cy + Math.sin(a) * R * 0.46, s = R * 0.035;
      ctx.save(); ctx.translate(x, y); ctx.rotate(a + Math.PI / 4);
      ctx.fillStyle = rgba(P.c2, 0.75); ctx.fillRect(-s / 2, -s / 2, s, s); ctx.restore();
    }
    if (st === 'listening' || st === 'speaking') {
      const n = 140; ctx.beginPath();
      for (let i = 0; i <= n; i++) {
        const a = (i / n) * TAU;
        const rr = R * 0.9 + Math.sin(a * 8 + t * 4) * R * (0.02 + L * 0.12);
        const x = cx + Math.cos(a) * rr, y = cy + Math.sin(a) * rr; i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
      }
      ctx.lineWidth = 1.6; ctx.strokeStyle = rgba(P.hot, 0.8); ctx.stroke();
    }
    core(ctx, cx, cy, R * (0.42 + L * 0.16 + (st === 'idle' ? 0.05 * Math.sin(t * 1.3) : 0)), P, 0.9);
  }

  function drawOrb(ctx, cx, cy, R, t, L, st, theme) {
    const P = PAL[theme] || PAL.koser;
    if (theme === 'kizil') orbKizil(ctx, cx, cy, R, t, L, st, P);
    else if (theme === 'altin') orbAltin(ctx, cx, cy, R, t, L, st, P);
    else orbKoser(ctx, cx, cy, R, t, L, st, P);
  }

  // ======================================================== ana döngü
  const cv = document.getElementById('hud');
  const ctx = cv.getContext('2d');
  let W = 0, Hh = 0, DPR = 1;
  function resize() {
    DPR = Math.min(window.devicePixelRatio || 1, 2);
    W = innerWidth; Hh = innerHeight;
    cv.width = W * DPR; cv.height = Hh * DPR;
  }
  addEventListener('resize', resize); resize();

  let glitchUntil = 0;
  function frame(now) {
    const t = (now - H.t0) / 1000;
    // seviye yumuşatma
    let tgt = H.target;
    if (H.state === 'speaking') tgt = 0.35 + 0.35 * Math.abs(Math.sin(t * 7.3) * Math.sin(t * 3.1 + 1)); // TTS seviyesi gelmez: canlandır
    if (H.state === 'thinking') tgt = 0.15;
    if (H.state === 'idle') tgt = 0;
    H.level += (tgt - H.level) * 0.18;
    H.target *= 0.92;

    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    const orbEl = document.getElementById('orb');
    const rc = orbEl.getBoundingClientRect();
    const cx = rc.left + rc.width / 2, cy = rc.top + rc.height / 2;
    const R = rc.width / 2 * 0.86;
    const P = PAL[H.theme] || PAL.koser;
    drawBackground(ctx, W, Hh, cx, cy, t, P);
    drawOrb(ctx, cx, cy, R, t, H.level, H.state, H.theme);

    // tema değişim parlaması
    if (H.flash > 0.01) {
      ctx.fillStyle = rgba(P.c, H.flash * 0.35); ctx.fillRect(0, 0, W, Hh); H.flash *= 0.9;
    }
    // kızıl: ara sıra bozulma
    if (H.theme === 'kizil') {
      if (now > glitchUntil + 2500 && Math.random() < 0.006) glitchUntil = now + 160;
      if (now < glitchUntil) {
        const y = Math.random() * Hh, hgt = 6 + Math.random() * 30;
        try { ctx.drawImage(cv, 0, y * DPR, W * DPR, hgt * DPR, (Math.random() - 0.5) * 24, y, W, hgt); } catch (e) {}
      }
    }
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);

  window.HUD = {
    setTheme(th) { if (PAL[th]) { if (th !== H.theme) H.flash = 1; H.theme = th; } },
    setState(s) { H.state = s; },
    setLevel(v) { H.target = Math.max(H.target, v); },
    drawOrb, PAL,
  };
})();
