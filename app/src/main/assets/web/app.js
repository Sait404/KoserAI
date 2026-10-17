/* KoserAI arayüz mantığı */
(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

  // ---------- yerel köprü (tarayıcıda önizleme için sahte sürüm)
  const N = window.KoserNative || (function mock() {
    let S = { onboarded: false, userName: 'efendim', theme: 'koser', wakePhrase: 'hey koser', wakeSensitivity: 2, ttsRate: 1, ttsPitch: 1,
      geminiModel: 'gemini-2.5-flash', city: 'İstanbul', autoSend: true, tvType: 'none', tvSecure: true, tvName: 'Televizyon' };
    const emit = (o) => setTimeout(() => window.Koser.onNative(JSON.stringify(o)), 0);
    const perms = { mic: true, contacts: false, phone: false, notif: true, overlay: false, access: false, assistant: false, battery: false, model: false, wakeRunning: false, recognizer: true, tts: true };
    return {
      ready() { emit({ type: 'settings', data: S }); emit({ type: 'perms', data: perms }); if (S.onboarded) emit({ type: 'koser', text: 'İyi akşamlar efendim. Koser hazır.' }); },
      listen() {
        emit({ type: 'state', value: 'listening' });
        let i = 0; const iv = setInterval(() => { emit({ type: 'level', v: Math.random() }); if (++i > 25) { clearInterval(iv);
          emit({ type: 'user', text: 'Saat kaç?' }); emit({ type: 'state', value: 'thinking' });
          setTimeout(() => { emit({ type: 'koser', text: 'Saat 23:41.' }); emit({ type: 'state', value: 'speaking' }); setTimeout(() => emit({ type: 'state', value: 'idle' }), 1800); }, 700); } }, 80);
      },
      stop() { emit({ type: 'state', value: 'idle' }); },
      sendText(t) { emit({ type: 'user', text: t }); emit({ type: 'state', value: 'thinking' }); setTimeout(() => { emit({ type: 'koser', text: 'Önizleme modundayım. Telefonda gerçek komutları çalıştırırım.' }); emit({ type: 'state', value: 'idle' }); }, 600); },
      getSettings() { return JSON.stringify(S); },
      saveSettings(j) { const o = JSON.parse(j); if (o.wakePhrase !== undefined && !/(^|\s)koser(\s|$)/i.test(o.wakePhrase.trim())) return 'Uyandırma cümlesinde mutlaka "Koser" kelimesi olmalı.'; Object.assign(S, o); return ''; },
      permStatus() { return JSON.stringify(perms); },
      requestPerm(n) { perms[n] = true; emit({ type: 'perms', data: perms }); },
      downloadModel() { let p = 0; const iv = setInterval(() => { p += 7; emit({ type: 'model', progress: Math.min(p, 100) }); if (p >= 100) { clearInterval(iv); perms.model = true; emit({ type: 'model', done: true, error: '' }); emit({ type: 'perms', data: perms }); } }, 120); },
      deleteModel() {}, teachWake() { let s = 0; const iv = setInterval(() => { s++; emit({ type: 'teach', step: s, msg: s < 4 ? `Şimdi "Hey Koser" de (${s}/3)` : 'Öğrendim!' }); if (s >= 4) clearInterval(iv); }, 1200); },
      cancelTeach() {}, setWake(b) { perms.wakeRunning = b; emit({ type: 'perms', data: perms }); return ''; },
      tvTest(op) { emit({ type: 'tvResult', text: 'Önizleme: ' + op }); }, tvReset() {}, testVoice() {}, openUrl(u) { window.open(u); },
      setPanelOpen() {}, appInfo() { return JSON.stringify({ version: '1.0.0', maker: 'Sait' }); }, vibrate() {},
    };
  })();

  let S = {};
  let PERMS = {};
  const tap = () => { try { N.vibrate(); } catch (e) {} };

  // ---------- ayarlar
  function save(patch) {
    const err = N.saveSettings(JSON.stringify(patch));
    if (!err) Object.assign(S, patch);
    return err;
  }

  function applySettings(s) {
    S = s;
    $$('[data-k]').forEach((el) => {
      const k = el.dataset.k; if (!(k in s)) return;
      if (el.type === 'checkbox') el.checked = !!s[k];
      else el.value = s[k];
    });
    setTheme(s.theme || 'koser', false);
    updateTvFields();
    if (!s.onboarded) showOnboard();
  }

  $$('[data-k]').forEach((el) => {
    const ev = el.type === 'range' || el.tagName === 'SELECT' || el.type === 'checkbox' ? 'change' : 'change';
    el.addEventListener(ev, () => {
      const k = el.dataset.k;
      let v = el.type === 'checkbox' ? el.checked : el.value;
      if (el.dataset.num !== undefined) v = parseFloat(v);
      const err = save({ [k]: v });
      if (k === 'wakePhrase') {
        $('#wakeErr').textContent = err || '';
        if (err) el.value = S.wakePhrase || 'hey koser';
        else toast('Uyandırma cümlesi kaydedildi. Daha iyi tanımam için "Öğret"e bas.');
      } else if (err) toast(err);
      if (k === 'tvType') updateTvFields();
    });
  });
  // yazarken anlık uyarı
  $('#wakePhrase').addEventListener('input', (e) => {
    const ok = /(^|\s)koser(\s|$)/i.test(e.target.value.trim().replace(/[İI]/g, 'i'));
    $('#wakeErr').textContent = ok ? '' : '"Koser" kelimesi mutlaka olmalı.';
  });

  function updateTvFields() {
    const t = $('#tvType').value;
    $$('.tvf').forEach((el) => { el.hidden = !el.dataset.tv.split(' ').includes(t); });
  }

  // ---------- tema
  function setTheme(th, persist = true) {
    document.body.dataset.theme = th;
    window.HUD && HUD.setTheme(th);
    $('#mode').textContent = { koser: 'KOSER', kizil: 'KIZIL', altin: 'ALTIN' }[th] || 'KOSER';
    $$('[data-theme-pick]').forEach((b) => b.classList.toggle('sel', b.dataset.themePick === th));
    if (persist) save({ theme: th });
  }
  $$('[data-theme-pick]').forEach((b) => b.addEventListener('click', () => { tap(); setTheme(b.dataset.themePick); }));

  // ---------- izinler
  const PERM_TEXT = {
    assistant: ['Koser varsayılan asistan ✓', 'Şu an başka bir asistan seçili'],
    overlay: ['İzin verildi ✓', 'Kapalı (arka plandan açılma için önerilir)'],
    access: ['Açık ✓', 'Kapalı'],
    contacts: ['İzin verildi ✓', 'İzin yok'],
    mic: ['İzin verildi ✓', 'İzin yok (zorunlu)'],
    phone: ['İzin verildi ✓', 'İzin yok'],
    notif: ['İzin verildi ✓', 'İzin yok'],
    battery: ['Kısıtlama yok ✓', 'Kısıtlı: arka planda kapanabilir'],
  };
  function applyPerms(p) {
    PERMS = p;
    $$('[data-perm-text]').forEach((el) => {
      const k = el.dataset.permText, ok = !!p[k];
      el.textContent = (PERM_TEXT[k] || ['Tamam', 'Yok'])[ok ? 0 : 1];
      el.classList.toggle('ok', ok); el.classList.toggle('no', !ok);
    });
    $$('[data-perm]').forEach((b) => b.classList.toggle('done', !!p[b.dataset.perm]));
    // model
    if (p.model) { $('#modelState').textContent = 'Hazır ✓'; $('#btnModel').textContent = 'SİL'; $('#modelBar').hidden = true; }
    else if (!p.modelDownloading) { $('#modelState').textContent = 'İndirilmedi (yaklaşık 35 MB)'; $('#btnModel').textContent = 'İNDİR'; }
    $('#wakeToggle').checked = !!S.wakeEnabled && !!p.model;
    const run = !!p.wakeRunning;
    $('#wakeRun').textContent = run ? 'Dinliyor ✓ (bildirimde görünür)' : (S.wakeEnabled ? 'Açık ama çalışmıyor — Koser\'i bir kez aç' : 'Kapalı');
    $('#wakeBadge').textContent = run ? 'AÇIK' : 'KAPALI';
    $('#wakeBadge').classList.toggle('on', run);
    $('#ttsWarn').hidden = p.tts !== false;
    if (p.recognizer === false) toast('Telefonda Google ses tanıma bulunamadı. Google uygulamasını yükle/güncelle.');
  }
  $$('[data-perm]').forEach((b) => b.addEventListener('click', () => { tap(); N.requestPerm(b.dataset.perm); }));

  $('#btnModel').addEventListener('click', () => {
    if (PERMS.model) {
      if (confirm('Ses modeli silinsin mi? "Hey Koser" çalışmaz.')) { N.deleteModel(); S.wakeEnabled = false; }
      return;
    }
    $('#modelBar').hidden = false; $('#modelState').textContent = 'İndiriliyor… (Wi-Fi önerilir)';
    N.downloadModel();
  });

  $('#wakeToggle').addEventListener('change', (e) => {
    const err = N.setWake(e.target.checked);
    if (err) { toast(err); e.target.checked = false; } else S.wakeEnabled = e.target.checked;
  });

  // öğretme
  $('#btnTeach').addEventListener('click', () => {
    if (!PERMS.model) { toast('Önce ses modelini indir.'); return; }
    $('#teach').hidden = false; $('#teachHeard').textContent = ''; $$('#teach .dots i').forEach((d) => d.classList.remove('on'));
    $('#teachMsg').textContent = 'Hazırlanıyor…'; openLayer('#teach');
    N.teachWake();
  });
  $('#teachClose').addEventListener('click', () => { N.cancelTeach(); closeLayer('#teach'); });

  // erişilebilirlik (önce açık açıklama)
  $('#btnAccess').addEventListener('click', () => { openLayer('#accessModal'); });
  $('#accessNo').addEventListener('click', () => closeLayer('#accessModal'));
  $('#accessYes').addEventListener('click', () => { closeLayer('#accessModal'); N.requestPerm('access'); });

  $('#btnGetKey').addEventListener('click', () => N.openUrl('https://aistudio.google.com/apikey'));
  $('#btnTestVoice').addEventListener('click', () => N.testVoice());
  $$('[data-tv-test]').forEach((b) => b.addEventListener('click', () => { $('#tvResult').textContent = 'Gönderiliyor…'; N.tvTest(b.dataset.tvTest); }));
  $('#btnTvReset').addEventListener('click', () => { N.tvReset(); toast('TV eşleşmesi sıfırlandı. Bir dahaki komutta TV yeniden izin isteyecek.'); });
  $('#btnPrivacy').addEventListener('click', () => openLayer('#privacy'));
  $('#btnRestartOnboard').addEventListener('click', () => { closeLayer('#settings'); save({ onboarded: false }); showOnboard(); });

  // ---------- katmanlar (panel / modal)
  const stack = [];
  function openLayer(sel) { const el = $(sel); el.hidden = false; if (!stack.includes(sel)) stack.push(sel); N.setPanelOpen(true); }
  function closeLayer(sel) { $(sel).hidden = true; const i = stack.indexOf(sel); if (i >= 0) stack.splice(i, 1); N.setPanelOpen(stack.length > 0 || !$('#onboard').hidden); }
  function closeTop() {
    if (stack.length) { const s = stack[stack.length - 1]; if (s === '#teach') N.cancelTeach(); closeLayer(s); return true; }
    if (document.body.classList.contains('typing')) { document.body.classList.remove('typing'); return true; }
    return false;
  }
  $$('[data-close]').forEach((b) => b.addEventListener('click', () => closeLayer('#' + b.closest('.panel').id)));
  $('#btnSettings').addEventListener('click', () => { tap(); openLayer('#settings'); applyPerms(JSON.parse(N.permStatus())); });
  $('#btnHelp').addEventListener('click', () => { tap(); openLayer('#help'); });

  // ---------- konuşma akışı
  const feed = $('#feed');
  function addMsg(role, text) {
    const d = document.createElement('div');
    d.className = 'msg ' + role;
    feed.appendChild(d);
    if (role === 'koser') {
      let i = 0; const step = Math.max(1, Math.round(text.length / 40));
      const iv = setInterval(() => { i += step; d.textContent = text.slice(0, i); feed.scrollTop = feed.scrollHeight; if (i >= text.length) clearInterval(iv); }, 22);
    } else d.textContent = text;
    while (feed.children.length > 30) feed.removeChild(feed.firstChild);
    feed.scrollTop = feed.scrollHeight;
  }

  const STATE_TEXT = { idle: 'BEKLEMEDE', listening: 'DİNLİYORUM', thinking: 'İŞLENİYOR', speaking: 'KONUŞUYOR' };
  function setState(s) {
    document.body.dataset.state = s;
    $('#stateLabel').textContent = STATE_TEXT[s] || s;
    window.HUD && HUD.setState(s);
    if (s !== 'listening') $('#partial').textContent = '';
  }

  const talk = () => { tap(); document.body.classList.remove('typing'); N.listen(); };
  $('#mic').addEventListener('click', talk);
  $('#orb').addEventListener('click', talk);

  $('#btnKeyboard').addEventListener('click', () => {
    tap(); document.body.classList.toggle('typing');
    if (document.body.classList.contains('typing')) setTimeout(() => $('#typeInput').focus(), 50);
  });
  $('#typeRow').addEventListener('submit', (e) => {
    e.preventDefault();
    const v = $('#typeInput').value.trim(); if (!v) return;
    $('#typeInput').value = ''; N.sendText(v);
  });

  // öneri çipleri
  const CHIPS = ['Saat kaç?', 'Hava nasıl?', "WhatsApp'tan Ahmet'e merhaba yaz", 'Televizyonu aç', 'Feneri aç', '10 dakika zamanlayıcı kur', 'Kızıl moda geç', 'Kimsin sen?'];
  $('#chips').innerHTML = '';
  CHIPS.forEach((c) => { const b = document.createElement('button'); b.textContent = c; b.onclick = () => { tap(); N.sendText(c); }; $('#chips').appendChild(b); });

  // ---------- komut listesi
  const HELP = [
    ['Uygulamalar', ["WhatsApp'ı aç", 'Instagram aç', 'YouTube aç', 'Kamerayı aç', 'Ayarları aç']],
    ['WhatsApp & arama', ["WhatsApp'tan Ahmet'e merhaba nasılsın yaz", "Anneme WhatsApp'tan eve geliyorum diye yaz", 'Babamı ara', "Ayşe'ye SMS at yoldayım"]],
    ['Televizyon', ['Televizyonu aç', 'Televizyonu kapat', 'Televizyonun sesini artır', 'TV sesini kıs', "TV'yi sessize al", 'Kanalı değiştir']],
    ['Telefon', ['Feneri aç', 'Sesi kıs', 'Sesi yüzde 50 yap', 'Sessize al', 'Ekran görüntüsü al', 'Telefonu kilitle', 'Wi-Fi ayarlarını aç', 'Şarj kaç?']],
    ['Zaman', ['Saat kaç?', 'Bugün günlerden ne?', 'Sabah 7 buçukta alarm kur', '5 dakika zamanlayıcı kur']],
    ['Bilgi', ['Hava nasıl?', "Yarın Ankara'da hava nasıl?", "YouTube'da Tarkan aç", "Google'da en iyi pilav tarifi ara", "Kadıköy'e yol tarifi", '125 çarpı 8 kaç eder']],
    ['Müzik', ['Müziği durdur', 'Devam et', 'Sonraki şarkı']],
    ['Notlar', ['Not al yarın faturayı öde', 'Notlarımı oku']],
    ['Koser', ['Kızıl moda geç', 'Altın moda geç', 'Normal moda dön', 'Kimsin sen?', 'Yazı tura at']],
    ['Sohbet (Gemini anahtarı ile)', ['Bana bir fıkra anlat', 'Kara delik nedir?', 'Bugün ne yemek yapsam?']],
  ];
  $('#helpBody').innerHTML = HELP.map(([g, items]) =>
    `<div class="help-group"><h3>${g}</h3>${items.map((i) => `<button data-say="${i.replace(/"/g, '&quot;')}">${i}</button>`).join('')}</div>`).join('') +
    '<p class="note">Her komuttan önce "Hey Koser" diyebilir ya da mikrofona basabilirsin. Bir örneğe dokunursan hemen dener.</p>';
  $$('#helpBody [data-say]').forEach((b) => b.addEventListener('click', () => { closeLayer('#help'); N.sendText(b.dataset.say); }));

  // ---------- gizlilik politikası
  $('#privacyBody').innerHTML = `
    <p><b>KoserAI Gizlilik Politikası</b><br>Geliştirici: Sait · İletişim: mardinsait60@gmail.com</p>
    <h3>Mikrofon</h3><p>Mikrofon yalnızca sen konuşmak için düğmeye bastığında, asistanı açtığında veya "Hey Koser" özelliğini açtığında kullanılır. "Hey Koser" dinlemesi tamamen telefonda yapılır; ses kaydedilmez ve hiçbir sunucuya gönderilmez. Komutların yazıya dökülmesi için telefonundaki Google konuşma tanıma servisi kullanılır.</p>
    <h3>Rehber ve arama</h3><p>Rehberin yalnızca "Ahmet'e yaz", "annemi ara" gibi komutlarda doğru kişiyi bulmak için telefonda okunur. Rehber bilgileri hiçbir yere gönderilmez ve saklanmaz.</p>
    <h3>Yapay zekâ</h3><p>Komut olmayan soruların ve sohbetin metni, yalnızca sen bir Gemini API anahtarı eklediysen, cevap üretilmesi için Google Gemini'ye gönderilir. Bu işlem Google'ın kendi gizlilik koşullarına tabidir. Anahtar yalnızca telefonunda saklanır.</p>
    <h3>Hava durumu</h3><p>Hava durumu için yalnızca şehir adı Open-Meteo servisine gönderilir. Konum izni istenmez.</p>
    <h3>Erişilebilirlik izni (isteğe bağlı)</h3><p>Açarsan, yalnızca sesli komutla hazırlanan WhatsApp mesajının Gönder düğmesine basmak ve ekran görüntüsü alma, kilitleme gibi sistem işlemleri için kullanılır. Ekran içeriği okunmaz, kaydedilmez, paylaşılmaz.</p>
    <h3>Televizyon</h3><p>TV komutları yalnızca ev ağındaki televizyonuna ya da senin girdiğin API adresine gönderilir.</p>
    <h3>Veri saklama</h3><p>Ayarlar ve notlar yalnızca telefonunda tutulur. Uygulamayı silince hepsi silinir. Koser reklam göstermez, veri satmaz, kullanıcı takibi yapmaz.</p>`;

  // ---------- ilk kurulum
  let obStep = 0;
  function showOnboard() {
    $('#onboard').hidden = false; obStep = 0; N.setPanelOpen(true);
    $$('.ob-step').forEach((s) => (s.hidden = s.dataset.step !== '0'));
    $('#obName').value = S.userName && S.userName !== 'efendim' ? S.userName : '';
    drawObOrb();
  }
  function obGo(n) {
    obStep = n;
    $$('.ob-step').forEach((s) => (s.hidden = +s.dataset.step !== n));
    if (n === 2 || n === 3) applyPerms(JSON.parse(N.permStatus()));
  }
  $$('[data-next]').forEach((b) => b.addEventListener('click', () => {
    tap();
    if (obStep === 1) save({ userName: $('#obName').value.trim() || 'efendim' });
    obGo(obStep + 1);
  }));
  $('[data-finish]').addEventListener('click', () => {
    tap(); save({ onboarded: true });
    $('#onboard').hidden = true; N.setPanelOpen(stack.length > 0);
    addMsg('koser', `Hazırım ${S.userName || 'efendim'}. Mikrofona dokun ya da bir öneriye bas.`);
  });
  let obRaf = 0;
  function drawObOrb() {
    const c = $('#obOrb'); const x = c.getContext('2d'); const t0 = performance.now();
    cancelAnimationFrame(obRaf);
    const loop = (now) => {
      if ($('#onboard').hidden) return;
      x.clearRect(0, 0, c.width, c.height);
      HUD.drawOrb(x, 130, 130, 78, (now - t0) / 1000, 0.15 + 0.1 * Math.sin(now / 300), 'speaking', S.theme || 'koser');
      obRaf = requestAnimationFrame(loop);
    };
    obRaf = requestAnimationFrame(loop);
  }

  // ---------- bildirim
  let toastT = 0;
  function toast(t) { const el = $('#toast'); el.textContent = t; el.hidden = false; clearTimeout(toastT); toastT = setTimeout(() => (el.hidden = true), 3800); }

  // ---------- saat
  function tick() { const d = new Date(); $('#clock').textContent = d.toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' }); }
  setInterval(tick, 10000); tick();

  try { $('#ver').textContent = JSON.parse(N.appInfo()).version; } catch (e) {}

  // ---------- yerelden gelen olaylar
  window.Koser = {
    onNative(json) {
      const e = JSON.parse(json);
      switch (e.type) {
        case 'settings': applySettings(e.data); break;
        case 'perms': applyPerms(e.data); break;
        case 'state': setState(e.value); break;
        case 'level': window.HUD && HUD.setLevel(e.v); break;
        case 'partial': $('#partial').textContent = e.text; break;
        case 'user': addMsg('user', e.text); break;
        case 'koser': addMsg('koser', e.text); break;
        case 'hint': addMsg('hint', e.text); break;
        case 'toast': toast(e.text); break;
        case 'theme': setTheme(e.id, false); S.theme = e.id; break;
        case 'open_settings': openLayer('#settings'); break;
        case 'stop': setState('idle'); break;
        case 'back': closeTop(); break;
        case 'tvResult': $('#tvResult').textContent = e.text; break;
        case 'model':
          if (e.progress !== undefined) { $('#modelBar').hidden = false; $('#modelBar i').style.width = e.progress + '%'; $('#modelState').textContent = `İndiriliyor… %${e.progress}`; }
          if (e.done) {
            $('#modelBar').hidden = true;
            if (e.error) { toast('Model indirilemedi: ' + e.error); $('#modelState').textContent = 'İndirilemedi'; }
            else toast('Ses modeli hazır. Şimdi "Öğret"e bas, sonra "Arka planda dinle"yi aç.');
          }
          break;
        case 'teach':
          if (e.partial) { $('#teachHeard').textContent = 'Duyduğum: ' + e.partial; break; }
          $('#teachMsg').textContent = e.msg || '';
          $$('#teach .dots i').forEach((d, i) => d.classList.toggle('on', e.step > i + 1 || e.step >= 4));
          break;
      }
    },
  };

  N.ready();
})();
