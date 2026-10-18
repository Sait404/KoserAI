/* KoserAI arayüz sesleri: tamamen WebAudio ile anlık sentezlenir (ses dosyası yok). */
(function () {
  let ac = null, master = null, enabled = true, persona = 'koser';
  function ctx() {
    if (!ac) {
      try {
        ac = new (window.AudioContext || window.webkitAudioContext)();
        master = ac.createGain(); master.gain.value = .55;
        const comp = ac.createDynamicsCompressor(); master.connect(comp); comp.connect(ac.destination);
      } catch (e) { return null; }
    }
    if (ac.state === 'suspended') ac.resume();
    return ac;
  }
  const P = () => (persona === 'ultron' ? .62 : persona === 'nova' ? 1.25 : 1); // karaktere göre perde
  const WAVE = () => (persona === 'ultron' ? 'sawtooth' : 'sine');

  function tone(f0, f1, dur, { type, gain = .2, delay = 0, attack = .008, q } = {}) {
    const a = ctx(); if (!a) return;
    const t = a.currentTime + delay;
    const o = a.createOscillator(), g = a.createGain();
    o.type = type || WAVE();
    o.frequency.setValueAtTime(f0 * P(), t);
    if (f1 && f1 !== f0) o.frequency.exponentialRampToValueAtTime(f1 * P(), t + dur);
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(gain, t + attack); g.gain.exponentialRampToValueAtTime(.0001, t + dur);
    let node = o;
    if (type === 'sawtooth' || type === 'square' || q) {
      const fl = a.createBiquadFilter(); fl.type = 'lowpass'; fl.frequency.value = (q || 2400) * P(); o.connect(fl); node = fl;
    }
    node.connect(g); g.connect(master);
    o.start(t); o.stop(t + dur + .05);
  }

  function noise(dur, { gain = .08, delay = 0, from = 400, to = 4000 } = {}) {
    const a = ctx(); if (!a) return;
    const t = a.currentTime + delay;
    const buf = a.createBuffer(1, Math.floor(a.sampleRate * dur), a.sampleRate);
    const d = buf.getChannelData(0); for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
    const src = a.createBufferSource(); src.buffer = buf;
    const bp = a.createBiquadFilter(); bp.type = 'bandpass'; bp.Q.value = 1.2;
    bp.frequency.setValueAtTime(from, t); bp.frequency.exponentialRampToValueAtTime(to, t + dur);
    const g = a.createGain(); g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(gain, t + dur * .3); g.gain.exponentialRampToValueAtTime(.0001, t + dur);
    src.connect(bp); bp.connect(g); g.connect(master); src.start(t); src.stop(t + dur);
  }

  const SOUNDS = {
    // uyanma: yükselen enerji + parıltı
    wake() { noise(.5, { gain: .06, from: 300, to: 5000 }); tone(220, 880, .45, { gain: .16 }); tone(1320, 1760, .25, { gain: .06, delay: .32, type: 'triangle' }); tone(2640, 2640, .18, { gain: .035, delay: .42, type: 'sine' }); },
    // dinlemeye hazır: çift bip
    listen() { tone(880, 880, .09, { gain: .1 }); tone(1320, 1320, .12, { gain: .09, delay: .1 }); },
    // düşünme: kısa veri cıvıltısı
    think() { for (let i = 0; i < 4; i++) tone(1800 + i * 260, 1800 + i * 260, .035, { gain: .03, delay: i * .055, type: 'square', q: 3500 }); },
    // araç çalışıyor
    tool() { tone(1400, 2100, .07, { gain: .05, type: 'triangle' }); },
    // tamamlandı
    done() { tone(660, 660, .1, { gain: .07 }); tone(990, 990, .16, { gain: .07, delay: .09 }); },
    // kapanış: inen enerji
    end() { tone(900, 200, .38, { gain: .12 }); noise(.35, { gain: .035, from: 3000, to: 300 }); },
    // hata: alçak vızıltı
    error() { tone(150, 120, .32, { gain: .1, type: 'sawtooth', q: 900 }); tone(150, 120, .32, { gain: .08, delay: .36, type: 'sawtooth', q: 900 }); },
    // dokunma
    tap() { tone(2200, 2000, .025, { gain: .025, type: 'sine', attack: .002 }); },
    // panel açılması
    open() { noise(.18, { gain: .03, from: 800, to: 3000 }); tone(1200, 1600, .1, { gain: .03, type: 'triangle' }); },
  };

  window.SFX = {
    play(n) { if (!enabled) return; try { SOUNDS[n] && SOUNDS[n](); } catch (e) {} },
    setEnabled(b) { enabled = !!b; },
    setPersona(p) { persona = p; },
  };
  // ilk dokunuşta ses motorunu hazırla
  addEventListener('pointerdown', () => ctx(), { once: true });
})();
