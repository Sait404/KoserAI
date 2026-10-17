/* KoserAI 2.0 arayüz mantığı */
(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const icon = (id, cls = '') => `<svg class="${cls}"><use href="#${id}"/></svg>`;

  // ================================================================ yerel köprü (tarayıcı önizlemesi için sahte)
  const N = window.KoserNative || mockNative();

  let S = {}, PERMS = {}, DATA = { tasks: [], reminders: [], memories: [], log: [], today: [], tomorrow: [], calendarPerm: false }, WX = null;
  let liveState = 'idle';
  const tap = () => { try { N.vibrate(); } catch (e) {} };

  const MOTTO = {
    koser: 'Hedeflerine odaklan,<br>geri kalan her şey yoluna girer.',
    ultron: 'Verimlilik bir tercih değil,<br>bir zorunluluktur.',
    nova: 'Bugün harika bir gün olacak.<br>Hadi başlayalım!',
  };
  const QUOTES = [
    'Disiplin, özgürlüğün anahtarıdır.', 'Küçük adımlar, büyük yolculukların başlangıcıdır.', 'Bugünün emeği, yarının gücüdür.',
    'Odak, en değerli süper gücündür.', 'Plan yap, uygula, tekrar et.', 'Zor günler, güçlü insanlar yetiştirir.',
    'Bir işi bitirmek, on işi düşünmekten iyidir.', 'Sabır ve süreklilik, yeteneği yener.', 'Erken başlayan, yolu yarılar.',
  ];

  // ================================================================ ayarlar
  function save(patch) { const err = N.saveSettings(JSON.stringify(patch)); if (!err) Object.assign(S, patch); return err; }
  const persona = () => (S.personaList || []).find((p) => p.id === (S.persona || 'koser')) || { id: 'koser', name: 'KOSER', tagline: 'DAHA İYİ BİR YARIN İÇİN', voice: 'Charon' };

  function applySettings(s) {
    S = s;
    const p = persona();
    document.body.dataset.p = p.id;
    ORB.setPersona(p.id);
    $$('.pName').forEach((e) => (e.textContent = p.name));
    $$('.pTag').forEach((e) => (e.textContent = p.tagline));
    $('#motto').innerHTML = MOTTO[p.id] || MOTTO.koser;
    $('#qBy').textContent = '- ' + cap(p.name);
    $('#micLabel').textContent = liveState === 'idle' ? `${dat(p.name)} sor...` : $('#micLabel').textContent;
    $$('[data-k]').forEach((el) => {
      const k = el.dataset.k; if (!(k in s)) return;
      if (document.activeElement === el) return;
      if (el.type === 'checkbox') el.checked = !!s[k]; else el.value = s[k];
    });
    updateTvFields();
    const nm = (s.userName && s.userName !== 'efendim') ? s.userName : 'Misafir';
    $('#profName').textContent = nm;
    $('#avatar').textContent = nm.trim().charAt(0).toLocaleUpperCase('tr');
    $('#profCity').textContent = (s.city || '—') + ' · ' + p.name + ' ile';
    renderPersonas();
    renderShortcuts();
    renderKeyBoxes();
    renderCards();
    if (!s.onboarded && $('#onboard').hidden) showOnboard();
  }
  const cap = (n) => { n = String(n || ''); return n.charAt(0) + n.slice(1).toLocaleLowerCase('tr'); };
  // Türkçe yönelme eki: Koser'e, Ultron'a, Nova'ya
  const dat = (n) => {
    const w = cap(n), low = w.toLocaleLowerCase('tr');
    const vs = low.match(/[aeıioöuü]/g) || ['e'];
    const back = 'aıou'.includes(vs[vs.length - 1]);
    const endsV = /[aeıioöuü]$/.test(low);
    return `${w}'${endsV ? 'y' : ''}${back ? 'a' : 'e'}`;
  };

  // ayar alanları: hem değişince hem yazarken (gecikmeli) kaydet
  const timers = {};
  $$('[data-k]').forEach((el) => {
    const doSave = () => {
      const k = el.dataset.k;
      let v = el.type === 'checkbox' ? el.checked : el.value;
      if (el.dataset.num !== undefined) v = parseFloat(v);
      const err = save({ [k]: v });
      if (k === 'wakePhrase') { $('#wakeErr').textContent = err || ''; if (err) el.value = S.wakePhrase || 'hey koser'; }
      else if (err) toast(err);
      if (k === 'tvType') updateTvFields();
      if (k === 'userName' || k === 'city') applySettings(S);
    };
    el.addEventListener('change', doSave);
    if (el.tagName === 'INPUT' && el.type !== 'checkbox' && el.type !== 'range' && el.dataset.k !== 'wakePhrase')
      el.addEventListener('input', () => { clearTimeout(timers[el.dataset.k]); timers[el.dataset.k] = setTimeout(doSave, 700); });
  });
  $('#wakePhrase').addEventListener('input', (e) => {
    const ok = /(^|\s)koser(\s|$)/i.test(e.target.value.trim().toLocaleLowerCase('tr'));
    $('#wakeErr').textContent = ok ? '' : '"Koser" kelimesi mutlaka olmalı.';
  });
  function updateTvFields() { const t = $('#tvType').value; $$('.tvf').forEach((el) => (el.hidden = !el.dataset.tv.split(' ').includes(t))); }

  // ================================================================ Gemini anahtarı (yapıştır + test et)
  function keyBoxHtml(id) {
    const has = PERMS.key;
    return `
      <div class="steps">
        <p><b>aistudio.google.com/apikey</b> sayfasını aç ve Google hesabınla giriş yap.</p>
        <p><b>"Create API key"</b> (API anahtarı oluştur) düğmesine bas ve anahtarı <b>kopyala</b>.</p>
        <p>Buraya dön, <b>YAPIŞTIR</b>'a ve sonra <b>TEST ET</b>'e bas.</p>
      </div>
      <button class="btn wide" data-open-key>AI STUDIO'YU AÇ</button>
      ${has ? `<div class="row" style="margin-top:8px"><div><b>Kayıtlı anahtar</b><small class="ok">${esc(S.geminiKey || '')} · ${esc(PERMS.liveModel || 'model seçilecek')}</small></div><button class="btn red" data-remove-key>KALDIR</button></div>` : ''}
      <div class="keyRow" style="margin-top:12px">
        <input type="password" id="${id}In" placeholder="${has ? 'Yeni anahtar (değiştirmek için)' : 'AIza… ile başlayan anahtar'}" autocomplete="off" autocapitalize="off" spellcheck="false">
        <button class="btn" data-paste="${id}">YAPIŞTIR</button>
      </div>
      <button class="btn fill wide" data-test="${id}">TEST ET VE KAYDET</button>
      <div class="keyMsg" id="${id}Msg"></div>`;
  }
  function renderKeyBoxes() {
    for (const [box, id] of [['#keyBox', 'k1'], ['#obKeyBox', 'k2']]) {
      const el = $(box); if (!el) continue;
      const typed = $('#' + id + 'In')?.value || '';
      const msg = $('#' + id + 'Msg');
      const keepMsg = msg && msg.classList.contains('show') ? { cls: msg.className, html: msg.innerHTML } : null;
      el.innerHTML = keyBoxHtml(id);
      $('#' + id + 'In').value = typed;
      if (keepMsg) { $('#' + id + 'Msg').className = keepMsg.cls; $('#' + id + 'Msg').innerHTML = keepMsg.html; }
    }
    $('#keyBadge').textContent = PERMS.key ? 'BAĞLI' : 'YOK';
    $('#keyBadge').classList.toggle('on', !!PERMS.key);
  }
  let keyTarget = 'k1';
  document.addEventListener('click', (e) => {
    const t = e.target.closest('[data-open-key],[data-paste],[data-test],[data-remove-key]'); if (!t) return;
    tap();
    if (t.dataset.openKey !== undefined) N.openUrl('https://aistudio.google.com/apikey');
    if (t.dataset.removeKey !== undefined) { if (confirm('Gemini anahtarı kaldırılsın mı?')) N.removeKey(); }
    if (t.dataset.paste) {
      const v = N.pasteClipboard();
      const inp = $('#' + t.dataset.paste + 'In');
      if (v) { inp.value = v.trim(); inp.type = 'password'; keyMsg(t.dataset.paste, 'busy', 'Yapıştırıldı. Şimdi TEST ET\'e bas.'); }
      else keyMsg(t.dataset.paste, 'no', 'Panoda bir şey yok. Önce AI Studio\'da anahtarı kopyala.');
    }
    if (t.dataset.test) {
      const v = $('#' + t.dataset.test + 'In').value.trim();
      if (!v) { keyMsg(t.dataset.test, 'no', 'Önce anahtarı yapıştır.'); return; }
      keyTarget = t.dataset.test;
      N.testKey(v);
    }
  });
  function keyMsg(id, cls, text) { const m = $('#' + id + 'Msg'); if (!m) return; m.className = 'keyMsg show ' + cls; m.textContent = text; }

  // ================================================================ izinler
  const PERM_LIST = [
    ['mic', 'Mikrofon', 'Seni duyabilmem için. Zorunlu.'],
    ['contacts', 'Rehber', '"Anneme yaz", "Ahmet\'i ara" için. Rehberin telefondan çıkmaz.'],
    ['phone', 'Telefon araması', 'Doğrudan arama yapabilmem için.'],
    ['calendar', 'Takvim', 'Randevularını okuyup yenilerini ekleyebilmem için.'],
    ['camera', 'Kamera', 'Gösterdiğin şeyi görebilmem için.'],
    ['notif', 'Bildirimler', 'Hatırlatmalar ve "Hey Koser" için.'],
    ['overlay', 'Üstte gösterme', 'Sen başka uygulamadayken de uygulama açabilmem için.'],
    ['battery', 'Pil kısıtlaması yok', 'Arka planda kapanmamam için.'],
  ];
  function permRowsHtml(keys) {
    return PERM_LIST.filter(([k]) => !keys || keys.includes(k)).map(([k, t, d]) => `
      <div class="row"><div><b>${t}</b><small data-pt="${k}">${d}</small></div><button class="btn ${PERMS[k] ? 'done' : ''}" data-perm="${k}">${PERMS[k] ? '✓' : 'İZİN'}</button></div>`).join('');
  }
  function applyPerms(p) {
    PERMS = p;
    $('#permRows').innerHTML = permRowsHtml();
    if (!$('#onboard').hidden) $('#obPerms').innerHTML = permRowsHtml(['mic', 'contacts', 'phone', 'calendar', 'notif', 'overlay']);
    $$('[data-pt]').forEach((el) => {
      const k = el.dataset.pt;
      if (k === 'assistant') { el.textContent = p.assistant ? 'Koser varsayılan asistan ✓' : 'Şu an başka bir asistan seçili'; el.className = p.assistant ? 'ok' : 'no'; }
      if (k === 'overlay' && el.closest('#secAi, details')) { if (!el.closest('#permRows')) { el.textContent = p.overlay ? 'İzin verildi ✓' : 'Kapalı'; el.className = p.overlay ? 'ok' : 'no'; } }
    });
    $$('[data-perm]').forEach((b) => { if (b.dataset.perm === 'assistant') b.classList.toggle('done', !!p.assistant); });
    if (p.model) { $('#modelState').textContent = 'Hazır ✓'; $('#btnModel').textContent = 'SİL'; $('#modelBar').hidden = true; }
    else if (!p.modelDownloading) { $('#modelState').textContent = 'İndirilmedi (~35 MB)'; $('#btnModel').textContent = 'İNDİR'; }
    $('#wakeToggle').checked = !!S.wakeEnabled && !!p.model;
    $('#wakeRun').textContent = p.wakeRunning ? 'Dinliyor ✓' : (S.wakeEnabled ? 'Açık ama çalışmıyor; Koser\'i bir kez aç' : 'Kapalı');
    $('#wakeBadge').textContent = p.wakeRunning ? 'AÇIK' : 'KAPALI'; $('#wakeBadge').classList.toggle('on', !!p.wakeRunning);
    renderKeyBoxes();
    renderCards();
  }
  document.addEventListener('click', (e) => { const b = e.target.closest('[data-perm]'); if (b) { tap(); N.requestPerm(b.dataset.perm); } });

  // ================================================================ karakterler
  function personaCards(sel) {
    return (S.personaList || []).map((p) => `
      <div class="pc ${p.id === (S.persona || 'koser') ? 'sel' : ''}" data-id="${p.id}" data-pick="${p.id}">
        <span class="sw"></span>
        <span class="tx"><b>${esc(p.name)}</b><small>${esc(p.tagline)} · Ses: ${esc(p.voice)}</small></span>
        ${sel ? `<button class="ed" data-edit="${p.id}" aria-label="Düzenle">${icon('i-edit')}</button>` : ''}
      </div>`).join('');
  }
  function renderPersonas() {
    $('#personaList').innerHTML = personaCards(true);
    $('#drawerPersonas').innerHTML = personaCards(false);
    $('#obPersonas').innerHTML = personaCards(false);
  }
  document.addEventListener('click', (e) => {
    const ed = e.target.closest('[data-edit]');
    if (ed) { e.stopPropagation(); tap(); editPersona(ed.dataset.edit); return; }
    const pc = e.target.closest('[data-pick]');
    if (pc) { tap(); if (pc.dataset.pick !== S.persona) { N.setPersona(pc.dataset.pick); S.persona = pc.dataset.pick; applySettings(S); toast(persona().name + ' etkin'); } }
  });
  function editPersona(id) {
    const p = (S.personaList || []).find((x) => x.id === id); if (!p) return;
    let voice = p.voice;
    openSheet('Karakteri düzenle', `
      <label class="f">Adı<input id="peName" value="${esc(p.name)}" maxlength="16"></label>
      <p class="note">Ses</p>
      <div class="voices" id="peVoices">${(S.voices || []).map((v) => `<button data-v="${v.id}" class="${v.id === voice ? 'sel' : ''}">${v.id}<small>${esc(v.desc)}</small></button>`).join('')}</div>
      <p class="note" style="margin-top:10px">Ses değişikliği bir sonraki konuşmada duyulur.</p>
      <button class="btn fill wide" id="peSave">KAYDET</button>`);
    $('#peVoices').addEventListener('click', (e) => { const b = e.target.closest('[data-v]'); if (!b) return; voice = b.dataset.v; $$('#peVoices button').forEach((x) => x.classList.toggle('sel', x === b)); });
    $('#peSave').onclick = () => { const n = $('#peName').value.trim() || p.name; N.savePersona(id, n, voice); closeSheet(); toast('Kaydedildi'); };
  }

  // ================================================================ ana sayfa kartları
  function fmtT(ms) { return new Date(ms).toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' }); }
  function renderCards() {
    // takvim
    let cal = 'Bugünün planlarını gör';
    if (!PERMS.calendar) cal = 'Takvim izni ver';
    else if (DATA.today && DATA.today.length) {
      const next = DATA.today.find((e) => e.end > Date.now()) || DATA.today[0];
      cal = `Bugün ${DATA.today.length} etkinlik · ${next.allDay ? 'tüm gün' : fmtT(next.begin)} ${next.title}`;
    } else cal = 'Bugün plan yok';
    $('#calSub').textContent = cal;
    // görevler
    const open = (DATA.tasks || []).filter((t) => !t.done);
    $('#taskSub').textContent = open.length ? `${open.length} bekleyen · ${open[0].title}` : 'Bekleyen iş yok';
    // hava
    if (WX && !WX.error) {
      $('#wxSub').textContent = `${WX.city}  |  ${WX.temp}°C`;
      $('#wxT').textContent = WX.temp + '°C'; $('#wxCity').textContent = WX.city; $('#wxD').textContent = WX.desc;
      $('#wx').classList.remove('err');
      ORB.drawWeatherIcon($('#wxIco'), WX.code, WX.isDay);
    } else if (WX && WX.error) {
      $('#wxSub').textContent = 'Bağlantı yok'; $('#wxT').textContent = '—'; $('#wxCity').textContent = WX.city || ''; $('#wxD').textContent = 'Ulaşılamadı';
    }
    renderProfileLists();
    if (!$('#sheet').hidden && sheetKind) renderSheet(sheetKind);
  }
  $('#cCal').onclick = () => { tap(); if (!PERMS.calendar) N.requestPerm('calendar'); else openKind('calendar'); };
  $('#cWx').onclick = $('#wx').onclick = () => { tap(); openKind('weather'); };
  $('#cTask').onclick = () => { tap(); openKind('tasks'); };
  $('#cSet').onclick = () => { tap(); openPage('settings'); };

  let qi = Math.floor(Math.random() * QUOTES.length);
  function rotateQuote() { qi = (qi + 1) % QUOTES.length; $('#qText').textContent = `“${QUOTES[qi]}”`; }
  $('#qText').textContent = `“${QUOTES[qi]}”`;
  setInterval(rotateQuote, 20000);
  $('#quote').onclick = rotateQuote;

  // ================================================================ alt paneller
  let sheetKind = null;
  function openSheet(title, html) { $('#sheetTitle').textContent = title; $('#sheetBody').innerHTML = html; $('#sheet').hidden = false; pushLayer('#sheet'); }
  function closeSheet() { $('#sheet').hidden = true; sheetKind = null; popLayer('#sheet'); }
  $('#sheetClose').onclick = closeSheet;
  $('#sheet').addEventListener('click', (e) => { if (e.target.id === 'sheet') closeSheet(); });
  function openKind(k) { sheetKind = k; renderSheet(k); $('#sheet').hidden = false; pushLayer('#sheet'); }

  function renderSheet(k) {
    const T = { tasks: 'Görevler', reminders: 'Hatırlatıcılar', calendar: 'Takvim', memory: 'Hafıza', brief: 'Günlük özet', log: 'Koser ne yaptı', weather: 'Hava durumu', tv: 'TV kumandası' };
    $('#sheetTitle').textContent = T[k] || '';
    let h = '';
    if (k === 'tasks') {
      h = `<form class="addRow" id="taskForm"><input id="taskIn" placeholder="Yeni görev…" autocomplete="off"><button>EKLE</button></form>
        <div class="list">${(DATA.tasks || []).length ? DATA.tasks.slice().sort((a, b) => a.done - b.done).map((t) => `
          <div class="item ${t.done ? 'done' : ''}"><button class="chk" data-toggle="${t.id}"></button><span class="tx"><b>${esc(t.title)}</b>${t.due ? `<small>Son: ${esc(t.due)}</small>` : ''}</span><button class="x" data-deltask="${t.id}">×</button></div>`).join('')
          : '<div class="empty">Henüz görev yok. "Hey Koser, yarın faturayı ödemeyi görevlerime ekle" diyebilirsin.</div>'}</div>`;
    } else if (k === 'reminders') {
      h = `<div class="list">${(DATA.reminders || []).length ? DATA.reminders.map((r) => `
          <div class="item"><span class="tx"><b>${esc(r.text)}</b><small>${new Date(r.at).toLocaleString('tr-TR', { weekday: 'long', day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' })}</small></span><button class="x" data-delrem="${r.id}">×</button></div>`).join('')
          : '<div class="empty">Bekleyen hatırlatma yok. "Akşam 8\'de ilacımı hatırlat" de.</div>'}</div>
        <button class="btn wide" data-say="Bekleyen hatırlatmalarımı oku">KOSER OKUSUN</button>`;
    } else if (k === 'calendar') {
      const ev = (list) => list.length ? list.map((e) => `<div class="item"><span class="tx"><b>${esc(e.title)}</b><small>${e.allDay ? 'Tüm gün' : fmtT(e.begin) + ' – ' + fmtT(e.end)}${e.location ? ' · ' + esc(e.location) : ''}</small></span></div>`).join('') : '<div class="empty">Etkinlik yok.</div>';
      h = !DATA.calendarPerm ? `<div class="empty">Takvimi görebilmem için izin gerekli.</div><button class="btn fill wide" data-perm="calendar">TAKVİM İZNİ VER</button>`
        : `<div class="sect" style="margin-top:0">Bugün</div><div class="list">${ev(DATA.today || [])}</div><div class="sect">Yarın</div><div class="list">${ev(DATA.tomorrow || [])}</div>
           <button class="btn wide" data-say="Yarın saat 10'da doktor randevusu ekle">ÖRNEK: "YARIN 10'A DOKTOR RANDEVUSU EKLE"</button>`;
    } else if (k === 'memory') {
      h = `<p class="note" style="margin-bottom:10px">Koser'in senin hakkında hatırladıkları. Yalnızca bu telefonda saklanır.</p><div class="list">${(DATA.memories || []).length ? DATA.memories.slice().reverse().map((m) => `
          <div class="item"><span class="tx"><b>${esc(m.text)}</b></span><button class="x" data-delmem="${m.id}">×</button></div>`).join('')
          : '<div class="empty">Henüz bir şey hatırlamıyorum. "Benim doğum günüm 3 Mart, unutma" diyebilirsin.</div>'}</div>`;
    } else if (k === 'brief') {
      const open = (DATA.tasks || []).filter((t) => !t.done);
      h = `<div class="list">
          <div class="item"><span class="tx"><b>Takvim</b><small>${!DATA.calendarPerm ? 'İzin yok' : (DATA.today || []).length ? DATA.today.map((e) => (e.allDay ? '' : fmtT(e.begin) + ' ') + esc(e.title)).join(' · ') : 'Bugün etkinlik yok'}</small></span></div>
          <div class="item"><span class="tx"><b>Görevler</b><small>${open.length ? open.map((t) => esc(t.title)).join(' · ') : 'Bekleyen yok'}</small></span></div>
          <div class="item"><span class="tx"><b>Hatırlatmalar</b><small>${(DATA.reminders || []).length ? DATA.reminders.slice(0, 3).map((r) => fmtT(r.at) + ' ' + esc(r.text)).join(' · ') : 'Yok'}</small></span></div>
          <div class="item"><span class="tx"><b>Hava</b><small>${WX && !WX.error ? `${esc(WX.city)} ${WX.temp}°C, ${esc(WX.desc)} (en yüksek ${WX.max}°, en düşük ${WX.min}°)` : '—'}</small></span></div>
          <div class="item"><span class="tx"><b>Bugün Koser</b><small>${(DATA.log || []).length ? DATA.log.length + ' iş yaptı' : 'Henüz bir iş yapmadı'}</small></span></div>
        </div><button class="btn fill wide" data-say="Bugün neler var, bana günlük özetimi anlat">SESLİ ÖZET AL</button>`;
    } else if (k === 'log') {
      h = `<div class="list">${(DATA.log || []).length ? DATA.log.map((l) => `<div class="item"><span class="tx"><b>${esc(l.text)}</b><small>${fmtT(l.t)}</small></span></div>`).join('') : '<div class="empty">Bugün henüz bir iş yapmadım.</div>'}</div>`;
    } else if (k === 'weather') {
      h = WX && !WX.error ? `<div style="display:flex;align-items:center;gap:16px;margin-bottom:12px"><canvas id="wxBig" width="150" height="150" style="width:96px;height:96px"></canvas>
          <div><div style="font-size:40px;color:var(--hot)">${WX.temp}°C</div><div style="color:var(--c)">${esc(WX.city)} · ${esc(WX.desc)}</div><div class="note">En yüksek ${WX.max}°, en düşük ${WX.min}°</div></div></div>
          <button class="btn fill wide" data-say="Yarın hava nasıl olacak?">YARIN NASIL?</button><button class="btn wide" data-page="settings">ŞEHRİ DEĞİŞTİR</button>`
        : `<div class="empty">Hava durumuna ulaşılamadı. İnternet bağlantını ve ayarlardaki şehri kontrol et.</div><button class="btn wide" data-page="settings">AYARLAR</button>`;
    } else if (k === 'tv') {
      h = tvRemote();
    }
    $('#sheetBody').innerHTML = h;
    if (k === 'weather' && $('#wxBig')) ORB.drawWeatherIcon($('#wxBig'), WX.code, WX.isDay);
    const tf = $('#taskForm');
    if (tf) tf.onsubmit = (e) => { e.preventDefault(); const v = $('#taskIn').value.trim(); if (v) { N.addTask(v); $('#taskIn').value = ''; } };
  }
  function tvRemote() {
    if (!S.tvType || S.tvType === 'none') return `<div class="empty">Henüz televizyon eklenmedi.</div><button class="btn fill wide" data-page="settings">TV EKLE</button>`;
    return `<div class="remote">
      <button class="btn" data-tv-test="on">AÇ</button><button class="btn" data-tv-test="off">KAPAT</button><button class="btn" data-tv-test="mute">SESSİZ</button>
      <button class="btn" data-tv-test="vol_up">SES +</button><button class="btn" data-tv-test="vol_down">SES −</button><button class="btn" data-tv-test="home">ANA EKRAN</button>
      <button class="btn" data-tv-test="ch_down">KANAL −</button><button class="btn" data-tv-test="ch_up">KANAL +</button><button class="btn" data-tv-test="source">KAYNAK</button>
    </div><small class="note tvResult"></small>`;
  }
  document.addEventListener('click', (e) => {
    const g = (a) => e.target.closest('[' + a + ']');
    let b;
    if ((b = g('data-toggle'))) { tap(); N.toggleTask(b.dataset.toggle); }
    else if ((b = g('data-deltask'))) N.deleteTask(b.dataset.deltask);
    else if ((b = g('data-delrem'))) N.deleteReminder(b.dataset.delrem);
    else if ((b = g('data-delmem'))) N.deleteMemory(b.dataset.delmem);
    else if ((b = g('data-say'))) { tap(); closeSheet(); closePages(); go('home'); N.sendText(b.dataset.say); }
    else if ((b = g('data-tv-test'))) { tap(); $$('.tvResult').forEach((x) => (x.textContent = 'Gönderiliyor…')); N.tvTest(b.dataset.tvTest); }
    else if ((b = g('data-sheet'))) { tap(); closeDrawer(); openKind(b.dataset.sheet); }
    else if ((b = g('data-page'))) { tap(); closeDrawer(); closeSheet(); openPage(b.dataset.page); }
    else if ((b = g('data-run-sc'))) { tap(); N.runShortcut(b.dataset.runSc); }
    else if ((b = g('data-del-sc'))) { const list = (S.shortcuts || []).filter((_, i) => i !== +b.dataset.delSc); save({ shortcuts: list }); renderShortcuts(); }
  });

  // ================================================================ profil listeleri
  function renderProfileLists() {
    const mem = (DATA.memories || []).slice(-4).reverse();
    $('#profMem').innerHTML = (mem.length ? mem.map((m) => `<div class="item"><span class="tx"><b>${esc(m.text)}</b></span></div>`).join('') : '<div class="empty">Henüz bir şey hatırlamıyorum.</div>') +
      `<button class="btn ghost wide" data-sheet="memory">TÜMÜ (${(DATA.memories || []).length})</button>`;
    const log = (DATA.log || []).slice(0, 5);
    $('#profLog').innerHTML = (log.length ? log.map((l) => `<div class="item"><span class="tx"><b>${esc(l.text)}</b><small>${fmtT(l.t)}</small></span></div>`).join('') : '<div class="empty">Bugün henüz bir iş yapmadım.</div>') +
      (DATA.log && DATA.log.length > 5 ? `<button class="btn ghost wide" data-sheet="log">TÜMÜ (${DATA.log.length})</button>` : '');
  }
  $('#btnEditProf').onclick = () => { openPage('settings'); const d = $$('#settings details').find((x) => x.textContent.includes('Profil')); if (d) d.open = true; };
  $('#btnProfSettings').onclick = () => openPage('settings');

  // ================================================================ uygulamalar / hızlı erişim
  const APPS = [
    ['i-eye', 'Kamera Gözü', 'Göster, anlatsın', () => { go('home'); N.vision(true); }],
    ['i-photo', 'Fotoğraf Sor', 'Fotoğrafı yorumlasın', () => { go('home'); N.pickPhoto(); }],
    ['i-brief', 'Günlük Özet', 'Bugün neler var', () => openKind('brief')],
    ['i-task', 'Görevler', 'Yapılacaklar', () => openKind('tasks')],
    ['i-bell', 'Hatırlatıcılar', 'Zamanı gelince', () => openKind('reminders')],
    ['i-cal', 'Takvim', 'Bugün ve yarın', () => { if (!PERMS.calendar) N.requestPerm('calendar'); else openKind('calendar'); }],
    ['i-brain', 'Hafıza', 'Seni tanıyor', () => openKind('memory')],
    ['i-tv', 'TV Kumandası', 'Televizyonun', () => openKind('tv')],
    ['i-globe', 'İnternette Ara', 'Haber, maç, döviz', () => { go('home'); N.sendText('Bugünün önemli haberlerini kısaca özetler misin?'); }],
    ['i-wx', 'Hava Durumu', 'Şehrin', () => openKind('weather')],
    ['i-log', 'Koser Ne Yaptı', 'Bugünkü işler', () => openKind('log')],
    ['i-set', 'Ayarlar', 'Kişiselleştir', () => openPage('settings')],
  ];
  $('#appGrid').innerHTML = APPS.map(([ic, t, s], i) => `<button class="tile" data-app="${i}">${icon(ic)}<b>${t}</b><small>${s}</small></button>`).join('');
  $('#appGrid').onclick = (e) => { const b = e.target.closest('[data-app]'); if (b) { tap(); APPS[+b.dataset.app][3](); } };

  const QUICK = ['Bugün neler var?', 'Hava nasıl?', 'Dolar ve euro kaç TL?', 'Son dakika haberleri', '10 dakikalık zamanlayıcı kur', 'Sabah 7\'de alarm kur',
    'Feneri aç', 'Müziği durdur', 'Televizyonu aç', 'Televizyonun sesini kıs', 'Yarın 10\'da doktor randevusu ekle', 'Bugün ne yaptın?', 'Bana bir fıkra anlat'];
  $('#quickChips').innerHTML = QUICK.map((q) => `<button class="chip" data-say="${esc(q)}">${esc(q)}</button>`).join('');

  function renderShortcuts() {
    const list = S.shortcuts || [];
    $('#quickShortcuts').innerHTML = list.length ? list.map((s) => `<div class="item"><span class="tx"><b>${esc(s.name)}</b><small>${esc((s.action || '').slice(0, 60))}</small></span><button class="btn" data-run-sc="${esc(s.name)}">ÇALIŞTIR</button></div>`).join('')
      : '<div class="empty">Kestirme yok. Ayarlar → Kestirmeler\'den ev otomasyonu ekleyebilirsin.</div>';
    $('#scList').innerHTML = list.map((s, i) => `<div class="item"><span class="tx"><b>${esc(s.name)}</b><small>${esc(s.action)}</small></span><button class="x" data-del-sc="${i}">×</button></div>`).join('');
    $('#quickTv').innerHTML = tvRemote();
  }
  $('#scAdd').onclick = () => {
    const name = $('#scName').value.trim(), action = $('#scAction').value.trim(), header = $('#scHeader').value.trim();
    if (!name || !action) { toast('Ad ve adres gerekli.'); return; }
    save({ shortcuts: [...(S.shortcuts || []), { name, action, header }] });
    $('#scName').value = $('#scAction').value = $('#scHeader').value = '';
    renderShortcuts(); toast('Kestirme eklendi');
  };
  $('#scTest').onclick = () => { const a = $('#scAction').value.trim(); if (!a) { toast('Önce adres yaz.'); return; } N.testShortcut(a, $('#scHeader').value.trim()); };

  // ================================================================ sekmeler / katmanlar
  function go(v) {
    document.body.dataset.v = v;
    $$('.view').forEach((x) => x.classList.toggle('on', x.id === v));
    $$('#nav button').forEach((b) => b.classList.toggle('on', b.dataset.go === v));
    if (v === 'home') setTimeout(ORB.resize, 50);
    N.setPanelOpen(layers.length > 0 || v !== 'home' || !$('#onboard').hidden);
  }
  $$('#nav button').forEach((b) => (b.onclick = () => { tap(); go(b.dataset.go); }));

  const layers = [];
  function pushLayer(s) { if (!layers.includes(s)) layers.push(s); N.setPanelOpen(true); }
  function popLayer(s) { const i = layers.indexOf(s); if (i >= 0) layers.splice(i, 1); N.setPanelOpen(layers.length > 0 || !$('#onboard').hidden || document.body.dataset.v !== 'home'); }
  function openPage(id) { closeSheet(); $('#' + id).hidden = false; pushLayer('#' + id); if (id === 'settings') applyPerms(JSON.parse(N.permStatus())); }
  function closePages() { $$('.page').forEach((p) => { if (!p.hidden) { p.hidden = true; popLayer('#' + p.id); } }); }
  $$('[data-close-page]').forEach((b) => (b.onclick = () => { const p = b.closest('.page'); p.hidden = true; popLayer('#' + p.id); }));
  function closeDrawer() { $('#drawer').hidden = true; popLayer('#drawer'); }
  $('#btnMenu').onclick = () => { tap(); $('#drawer').hidden = false; pushLayer('#drawer'); };
  $('#drawer').addEventListener('click', (e) => { if (e.target.id === 'drawer') closeDrawer(); });
  function back() {
    const top = layers[layers.length - 1];
    if (top === '#sheet') return closeSheet();
    if (top === '#drawer') return closeDrawer();
    if (top === '#teach') { N.cancelTeach(); $('#teach').hidden = true; return popLayer('#teach'); }
    if (top) { $(top).hidden = true; return popLayer(top); }
    if (document.body.dataset.v !== 'home') { go('home'); popLayer('x'); }
  }

  // ================================================================ konuşma
  const feed = $('#feed');
  let uBubble = null, mBubble = null;
  function bubble(cls, text) { const d = document.createElement('div'); d.className = 'b ' + cls; d.textContent = text; feed.appendChild(d); trim(); feed.scrollTop = feed.scrollHeight; return d; }
  function trim() { while (feed.children.length > 40) feed.removeChild(feed.firstChild); }
  function onUser(t, fin) {
    if (!t) return;
    if (!uBubble) { uBubble = bubble('u live', t); mBubble = null; } else uBubble.textContent = t;
    if (fin) { uBubble.classList.remove('live'); uBubble = null; }
    feed.scrollTop = feed.scrollHeight;
  }
  function onModel(t, fin) {
    if (uBubble) { uBubble.classList.remove('live'); uBubble = null; }
    if (!mBubble) mBubble = bubble('m', t); else mBubble.textContent = t;
    if (fin) mBubble = null;
    feed.scrollTop = feed.scrollHeight;
  }
  const toolEls = {};
  function onTool(name, label) { toolEls[name] = bubble('sys', label + '…'); mBubble = null; }
  function onToolDone(name, ok) { const el = toolEls[name]; if (el) { el.classList.add('done'); el.textContent = el.textContent.replace('…', ok ? ' ✓' : ' ✗'); delete toolEls[name]; } }

  const STATE_TXT = { connecting: 'BAĞLANIYOR', listening: 'DİNLİYORUM', thinking: 'DÜŞÜNÜYOR', speaking: 'KONUŞUYOR', idle: '' };
  const MIC_TXT = { connecting: 'Bağlanıyor…', listening: 'Dinliyorum…', thinking: 'Düşünüyor…', speaking: 'Konuşuyor… (sözünü kesebilirsin)' };
  function setState(s) {
    liveState = s;
    document.body.dataset.state = s;
    ORB.setState(s);
    const talking = s !== 'idle';
    if (talking && !document.body.classList.contains('talking')) { document.body.classList.add('talking'); if (!feed.children.length) bubble('sys done', persona().name + ' hazır'); }
    if (!talking) { document.body.classList.remove('talking'); $('#typeRow').classList.remove('on'); $('#tMute').classList.remove('on'); $('#tCam').classList.remove('on'); uBubble = mBubble = null; }
    $('#stateLine').textContent = STATE_TXT[s] || '';
    $('#micLabel').textContent = talking ? (MIC_TXT[s] || '') : `${dat(persona().name)} sor...`;
  }

  const talk = () => { tap(); if (document.body.dataset.v !== 'home') go('home'); N.talk(); };
  $('#mic').onclick = talk;
  $('#orbHit').onclick = talk;
  $('#tKb').onclick = () => { tap(); $('#typeRow').classList.toggle('on'); if ($('#typeRow').classList.contains('on')) setTimeout(() => $('#typeIn').focus(), 50); };
  $('#typeRow').onsubmit = (e) => { e.preventDefault(); const v = $('#typeIn').value.trim(); if (!v) return; $('#typeIn').value = ''; N.sendText(v); };
  $('#tMute').onclick = () => { tap(); const on = !$('#tMute').classList.contains('on'); $('#tMute').classList.toggle('on', on); N.setMute(on); toast(on ? 'Mikrofon kapalı' : 'Mikrofon açık'); };
  $('#tCam').onclick = () => { tap(); const on = !$('#tCam').classList.contains('on'); N.vision(on); };
  $('#tPhoto').onclick = () => { tap(); N.pickPhoto(); };

  // ================================================================ Hey Koser
  $('#btnModel').onclick = () => {
    if (PERMS.model) { if (confirm('Ses modeli silinsin mi? "Hey Koser" çalışmaz.')) { N.deleteModel(); S.wakeEnabled = false; } return; }
    $('#modelBar').hidden = false; $('#modelState').textContent = 'İndiriliyor… (Wi-Fi önerilir)'; N.downloadModel();
  };
  $('#wakeToggle').onchange = (e) => { const err = N.setWake(e.target.checked); if (err) { toast(err); e.target.checked = false; } else S.wakeEnabled = e.target.checked; };
  $('#btnTeach').onclick = () => {
    if (!PERMS.model) { toast('Önce ses modelini indir.'); return; }
    $('#teach').hidden = false; pushLayer('#teach'); $('#teachHeard').textContent = ''; $('#teachMsg').textContent = 'Hazırlanıyor…';
    $$('#teach .mdots i').forEach((d) => d.classList.remove('on')); N.teachWake();
  };
  $('#teachClose').onclick = () => { N.cancelTeach(); $('#teach').hidden = true; popLayer('#teach'); };
  $('#btnTvReset').onclick = () => { N.tvReset(); toast('TV eşleşmesi sıfırlandı.'); };
  $('#btnClearHist').onclick = () => { if (confirm('Koser\'in hatırladığı son konuşmalar silinsin mi? (Hafızadaki bilgiler kalır)')) { N.clearHistory(); toast('Silindi'); } };
  $('#btnRedoOnboard').onclick = () => { closePages(); save({ onboarded: false }); showOnboard(); };

  // ================================================================ gizlilik
  $('#privacyBody').innerHTML = `
    <p><b style="color:var(--hot)">KoserAI Gizlilik Politikası</b><br>Geliştirici: Sait · İletişim: mardinsait60@gmail.com</p>
    <h3>Hesap yok, reklam yok, takip yok</h3><p>KoserAI hesap açtırmaz, reklam göstermez, seni takip etmez ve geliştiriciye hiçbir veri göndermez.</p>
    <h3>Yapay zekâ (Google Gemini)</h3><p>Gerçek zamanlı konuşma, arama ve görme için sesin, yazdıkların, gösterdiğin kamera görüntüleri ve fotoğraflar, senin kendi Gemini API anahtarınla doğrudan Google'a gönderilir ve Google'ın koşullarına tabidir. Ücretsiz katmanda Google bu verileri ürünlerini geliştirmek için kullanabilir. Anahtarın yalnızca telefonunda saklanır.</p>
    <h3>"Hey Koser"</h3><p>Uyandırma cümlesi tamamen telefonda, internet olmadan algılanır. Bu dinleme sırasında ses kaydedilmez ve hiçbir yere gönderilmez.</p>
    <h3>Rehber, takvim, arama</h3><p>Rehber ve takvim yalnızca senin komutların için telefonda okunur. Bunlar, bir isteğe cevap verebilmek için gerektiği kadarıyla (ör. "bugün neler var?" sorusunda etkinlik başlıkları) Gemini'ye iletilebilir; başka hiçbir yere gönderilmez.</p>
    <h3>Hafıza, görevler, hatırlatıcılar</h3><p>Hepsi yalnızca bu telefonda saklanır. Uygulamayı silince silinir. İstediğin zaman uygulamadan tek tek silebilirsin.</p>
    <h3>Hava durumu</h3><p>Yalnızca şehir adın Open-Meteo hava servisine gönderilir. Konum izni istenmez.</p>
    <h3>Televizyon ve kestirmeler</h3><p>Komutlar yalnızca ev ağındaki televizyonuna ya da senin girdiğin adreslere gönderilir.</p>`;

  // ================================================================ ilk kurulum
  let ob = 0;
  function showOnboard() {
    $('#onboard').hidden = false; ob = 0; N.setPanelOpen(true);
    $$('.ob').forEach((s) => (s.hidden = s.dataset.step !== '0'));
    obOrbLoop();
  }
  function obGo(n) {
    ob = n; $$('.ob').forEach((s) => (s.hidden = +s.dataset.step !== n));
    if (n === 1) { $('#obName').value = S.userName && S.userName !== 'efendim' ? S.userName : ''; $('#obCity').value = S.city || ''; }
    if (n === 3) applyPerms(JSON.parse(N.permStatus()));
    if (n === 2) renderKeyBoxes();
  }
  $$('[data-next]').forEach((b) => (b.onclick = () => {
    tap();
    if (ob === 1) save({ userName: $('#obName').value.trim() || 'efendim', city: $('#obCity').value.trim() || 'İstanbul' });
    obGo(ob + 1);
  }));
  $('[data-finish]').onclick = () => {
    tap(); save({ onboarded: true }); $('#onboard').hidden = true; N.setPanelOpen(layers.length > 0);
    applySettings(S); N.refresh();
    toast(`Hazırım. Mikrofona dokun ya da "${dat(persona().name)} sor" düğmesine bas.`);
  };
  let obRaf = 0;
  function obOrbLoop() {
    const c = $('#obOrb'), x = c.getContext('2d'), t0 = performance.now();
    cancelAnimationFrame(obRaf);
    const loop = (now) => {
      if ($('#onboard').hidden) return;
      x.clearRect(0, 0, 300, 300);
      ORB.drawOrb(x, 150, 150, 108, (now - t0) / 1000, 'speaking', .25 + .2 * Math.abs(Math.sin(now / 260)), S.persona || 'koser');
      obRaf = requestAnimationFrame(loop);
    };
    obRaf = requestAnimationFrame(loop);
  }

  // ================================================================ bildirim
  let toastT = 0;
  function toast(t) { const el = $('#toast'); el.textContent = t; el.hidden = false; clearTimeout(toastT); toastT = setTimeout(() => (el.hidden = true), 3600); }

  try { const info = JSON.parse(N.appInfo()); $('#ver').textContent = info.version; $('#verD').textContent = info.version; } catch (e) {}

  // ================================================================ yerelden gelen olaylar
  window.Koser = {
    onNative(json) {
      const e = JSON.parse(json);
      switch (e.type) {
        case 'settings': applySettings(e.data); break;
        case 'perms': applyPerms(e.data); break;
        case 'data': DATA = e.data; renderCards(); break;
        case 'weather': WX = e.data; renderCards(); break;
        case 'state': setState(e.value); break;
        case 'level': ORB.setLevel(e.v); break;
        case 'user': onUser(e.text, e.final); break;
        case 'model': onModel(e.text, e.final); break;
        case 'tool': onTool(e.name, e.label); break;
        case 'tool_done': onToolDone(e.name, e.ok); break;
        case 'hint': bubble('sys done', e.text); break;
        case 'live_error':
          if (document.body.classList.contains('talking')) bubble('err', e.text);
          toast(e.text);
          if (e.code === 'nokey') { openPage('settings'); $('#secAi').open = true; }
          if (e.code === 'key') { openPage('settings'); $('#secAi').open = true; }
          break;
        case 'vision': $('#tCam').classList.toggle('on', !!e.on); if (e.on) bubble('sys done', 'Kamera açık: göster, anlatayım'); break;
        case 'persona': S.persona = e.data.id; applySettings(S); break;
        case 'keyResult':
          if (e.busy) keyMsg(keyTarget, 'busy', 'Anahtar deneniyor…');
          else { keyMsg(keyTarget, e.ok ? 'ok' : 'no', e.message); if (e.ok) { const i = $('#' + keyTarget + 'In'); if (i) i.value = ''; } }
          break;
        case 'toast': toast(e.text); break;
        case 'back': back(); break;
        case 'tvResult': $$('.tvResult').forEach((x) => (x.textContent = e.text)); break;
        case 'vosk':
          if (e.progress !== undefined) { $('#modelBar').hidden = false; $('#modelBar i').style.width = e.progress + '%'; $('#modelState').textContent = `İndiriliyor… %${e.progress}`; }
          if (e.done) { $('#modelBar').hidden = true; if (e.error) toast('Model indirilemedi: ' + e.error); else toast('Ses modeli hazır. Şimdi "Öğret"e bas, sonra "Arka planda dinle"yi aç.'); }
          break;
        case 'teach':
          if (e.partial) { $('#teachHeard').textContent = 'Duyduğum: ' + e.partial; break; }
          $('#teachMsg').textContent = e.msg || '';
          $$('#teach .mdots i').forEach((d, i) => d.classList.toggle('on', e.step > i + 1 || e.step >= 4));
          break;
        case 'stop': setState('idle'); break;
      }
    },
  };

  N.ready();

  // ================================================================ tarayıcı önizlemesi
  function mockNative() {
    const emit = (o) => setTimeout(() => window.Koser.onNative(JSON.stringify(o)), 0);
    const st = {
      onboarded: false, userName: 'efendim', city: 'Mardin', persona: 'koser', wakePhrase: 'hey koser', wakeSensitivity: 2, bargeIn: true, greetOnWake: true, autoEndSec: 45,
      tvType: 'none', tvSecure: true, tvName: 'Salon TV', shortcuts: [], geminiKey: '',
      personaList: [
        { id: 'koser', name: 'KOSER', voice: 'Charon', tagline: 'DAHA İYİ BİR YARIN İÇİN' },
        { id: 'ultron', name: 'ULTRON', voice: 'Algenib', tagline: 'SINIRLAR İNSANLAR İÇİNDİR' },
        { id: 'nova', name: 'NOVA', voice: 'Aoede', tagline: 'ENERJİ SENDE' }],
      voices: [{ id: 'Charon', desc: 'Bilgilendirici, tok erkek' }, { id: 'Algenib', desc: 'Pürüzlü, derin erkek' }, { id: 'Aoede', desc: 'Ferah kadın' }, { id: 'Kore', desc: 'Kararlı kadın' }],
    };
    const pm = { mic: true, contacts: true, phone: false, calendar: true, camera: false, notif: true, overlay: false, assistant: false, battery: false, model: false, key: false, liveModel: '' };
    const now = Date.now(), h = 3600e3;
    const data = { calendarPerm: true, today: [{ title: 'Ekip toplantısı', begin: now + 2 * h, end: now + 3 * h, allDay: false }], tomorrow: [],
      tasks: [{ id: 'a', title: 'Faturayı öde', done: false }, { id: 'b', title: 'Spor salonu', done: true }], reminders: [], memories: [{ id: 'm', text: 'Kullanıcının adı Sait.' }], log: [] };
    let talking = false;
    return {
      ready() { emit({ type: 'settings', data: st }); emit({ type: 'perms', data: pm }); emit({ type: 'data', data }); emit({ type: 'weather', data: { city: 'Mardin', temp: 16, desc: 'Parçalı bulutlu', code: 2, isDay: false, max: 19, min: 9 } }); },
      talk() {
        if (talking) { talking = false; emit({ type: 'state', value: 'idle' }); return; }
        talking = true; emit({ type: 'state', value: 'connecting' });
        setTimeout(() => emit({ type: 'state', value: 'listening' }), 600);
        let i = 0; const iv = setInterval(() => { emit({ type: 'level', v: Math.random() * .8 }); if (++i === 12) emit({ type: 'user', text: 'Bugün neler var', final: false }); if (i > 20) { clearInterval(iv);
          emit({ type: 'user', text: 'Bugün neler var?', final: true }); emit({ type: 'state', value: 'thinking' }); emit({ type: 'tool', name: 'get_daily_brief', label: 'Günü özetliyor' });
          setTimeout(() => { emit({ type: 'tool_done', name: 'get_daily_brief', ok: true }); emit({ type: 'state', value: 'speaking' });
            let w = 0; const words = 'Bugün saat on dörtte ekip toplantın var. Bir bekleyen görevin var: faturayı ödemek. Mardin şu an on altı derece.'.split(' ');
            const sp = setInterval(() => { emit({ type: 'level', v: .3 + Math.random() * .6 }); w++; emit({ type: 'model', text: words.slice(0, w).join(' '), final: w >= words.length }); if (w >= words.length) { clearInterval(sp); setTimeout(() => emit({ type: 'state', value: 'listening' }), 500); } }, 140);
          }, 900); } }, 90);
      },
      stopTalk() { talking = false; emit({ type: 'state', value: 'idle' }); },
      sendText(t) { if (!talking) { this.talk(); } setTimeout(() => emit({ type: 'user', text: t, final: true }), 700); },
      setMute() {}, vision(on) { emit({ type: 'vision', on }); }, pickPhoto() {},
      getSettings() { return JSON.stringify(st); },
      saveSettings(j) { const o = JSON.parse(j); if (o.wakePhrase !== undefined && !/(^|\s)koser(\s|$)/i.test(o.wakePhrase)) return 'Uyandırma cümlesinde mutlaka "Koser" kelimesi olmalı.'; Object.assign(st, o); return ''; },
      permStatus() { return JSON.stringify(pm); }, requestPerm(n) { pm[n] = true; emit({ type: 'perms', data: pm }); },
      pasteClipboard() { return 'AIzaSyDEMO-ornek-anahtar-1234567890'; },
      testKey() { emit({ type: 'keyResult', busy: true }); setTimeout(() => { pm.key = true; pm.liveModel = 'gemini-3.8-live'; st.geminiKey = 'AIza••••••••7890'; emit({ type: 'keyResult', ok: true, message: 'Anahtar çalışıyor ✓ Canlı ses modeli: gemini-3.8-live' }); emit({ type: 'settings', data: st }); emit({ type: 'perms', data: pm }); }, 900); },
      removeKey() { pm.key = false; st.geminiKey = ''; emit({ type: 'perms', data: pm }); },
      setPersona(id) { st.persona = id; }, savePersona(id, n, v) { const p = st.personaList.find((x) => x.id === id); p.name = n; p.voice = v; emit({ type: 'settings', data: st }); },
      refresh() {}, addTask(t) { data.tasks.push({ id: String(Math.random()), title: t, done: false }); emit({ type: 'data', data }); },
      toggleTask(id) { const t = data.tasks.find((x) => x.id === id); t.done = !t.done; emit({ type: 'data', data }); }, deleteTask(id) { data.tasks = data.tasks.filter((x) => x.id !== id); emit({ type: 'data', data }); },
      deleteReminder() {}, deleteMemory() {}, clearHistory() {}, runShortcut() {}, testShortcut() {},
      tvTest(op) { emit({ type: 'tvResult', text: 'Önizleme: ' + op }); }, tvReset() {}, downloadModel() {}, deleteModel() {}, teachWake() {}, cancelTeach() {},
      setWake() { return ''; }, openUrl() {}, setPanelOpen() {}, appInfo() { return JSON.stringify({ version: '2.0.0', maker: 'Sait' }); }, vibrate() {},
    };
  }
})();
