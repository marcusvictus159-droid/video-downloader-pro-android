(() => {
  'use strict';

  const $ = (s) => document.querySelector(s);
  const $$ = (s) => Array.from(document.querySelectorAll(s));

  const store = {
    get(k, d) {
      try {
        const v = localStorage.getItem('vdp_' + k);
        return v === null ? d : JSON.parse(v);
      } catch (e) { return d; }
    },
    set(k, v) {
      try { localStorage.setItem('vdp_' + k, JSON.stringify(v)); } catch (e) { /* sin almacenamiento */ }
    },
  };

  const state = {
    format: store.get('format', 'mp4'),
    queue: [],
    running: false,
    ready: false,
    updating: false,
    engineError: '',
    tab: 'new',
  };
  let history = store.get('history', []);
  let YtDlp = null;
  let seq = 0;

  // ----------------------------------------------------------- utilidades
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));

  const icon = (name) => '<svg class="ico"><use href="#i-' + name + '"/></svg>';

  let toastTimer = null;
  function toast(msg) {
    const t = $('#toast');
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, 2600);
  }

  const consoleEl = $('#console');
  function log(line) {
    if (!line) return;
    consoleEl.textContent += line + '\n';
    const lines = consoleEl.textContent.split('\n');
    if (lines.length > 300) consoleEl.textContent = lines.slice(-300).join('\n');
    consoleEl.scrollTop = consoleEl.scrollHeight;
  }

  function fmtEta(sec) {
    if (!sec || sec < 0) return '';
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = Math.floor(sec % 60);
    return h > 0
      ? h + ':' + String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0')
      : m + ':' + String(s).padStart(2, '0');
  }

  function ago(ts) {
    if (!ts) return 'nunca';
    const min = Math.floor((Date.now() - ts) / 60000);
    if (min < 2) return 'hace un momento';
    if (min < 60) return 'hace ' + min + ' min';
    const h = Math.floor(min / 60);
    if (h < 24) return 'hace ' + h + (h === 1 ? ' hora' : ' horas');
    const d = Math.floor(h / 24);
    return 'hace ' + d + (d === 1 ? ' día' : ' días');
  }

  function extractUrls(text) {
    const found = (text || '').match(/https?:\/\/[^\s"'<>]+/gi) || [];
    return Array.from(new Set(found.map((u) => u.replace(/[),.;]+$/, ''))));
  }

  // Clave estable por video para el historial.
  function videoKey(url) {
    try {
      const u = new URL(url);
      const host = u.hostname.replace(/^(www|m)\./, '');
      if (host === 'youtu.be') return 'yt:' + u.pathname.slice(1).split('/')[0];
      if (host.endsWith('youtube.com')) {
        const v = u.searchParams.get('v');
        if (v) return 'yt:' + v;
        const m = u.pathname.match(/^\/(shorts|embed|live)\/([\w-]{6,})/);
        if (m) return 'yt:' + m[2];
      }
      return u.origin + u.pathname + u.search;
    } catch (e) { return url; }
  }

  function parseTitle(line) {
    let m = line.match(/Destination:\s*(.+)$/);
    if (!m) m = line.match(/Merging formats into\s+"?(.+?)"?$/);
    if (!m) return null;
    let name = m[1].split('/').pop();
    name = name.replace(/\.[A-Za-z0-9]{2,4}$/, '').replace(/\.f\d+$/, '');
    return name || null;
  }

  function nativePlugin(name) {
    const c = window.Capacitor;
    if (!c) return null;
    if (c.Plugins && c.Plugins[name]) return c.Plugins[name];
    return null;
  }

  // ----------------------------------------------------------------- tema
  function isDark() {
    const t = store.get('theme', 'auto');
    if (t === 'dark') return true;
    if (t === 'light') return false;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  }

  function syncSystemBars() {
    const sb = nativePlugin('StatusBar');
    if (!sb) return;
    const dark = isDark();
    try {
      sb.setBackgroundColor({ color: dark ? '#0e1218' : '#f2f4f8' });
      sb.setStyle({ style: dark ? 'DARK' : 'LIGHT' });
    } catch (e) { /* sin barra de estado */ }
  }

  function applyTheme() {
    const t = store.get('theme', 'auto');
    if (t === 'auto') document.documentElement.removeAttribute('data-theme');
    else document.documentElement.setAttribute('data-theme', t);
    $$('[data-theme]').forEach((b) => b.setAttribute('aria-checked', String(b.dataset.theme === t)));
    syncSystemBars();
  }
  $$('[data-theme]').forEach((b) => b.addEventListener('click', () => {
    store.set('theme', b.dataset.theme);
    applyTheme();
  }));
  if (window.matchMedia) {
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => syncSystemBars();
    if (mq.addEventListener) mq.addEventListener('change', onChange);
  }

  // ----------------------------------------------------------- pestañas
  function showTab(name) {
    state.tab = name;
    $$('.screen').forEach((s) => s.classList.toggle('active', s.id === 's-' + name));
    $$('.tab').forEach((t) => {
      const on = t.dataset.tab === name;
      t.classList.toggle('active', on);
      if (on) t.setAttribute('aria-current', 'page'); else t.removeAttribute('aria-current');
    });
    if (name === 'lib') libOpen();
  }
  $$('.tab').forEach((t) => t.addEventListener('click', () => showTab(t.dataset.tab)));
  $('#goNew').addEventListener('click', () => { showTab('new'); $('#urls').focus(); });

  // El botón «atrás» del sistema vuelve a Nuevo y, desde ahí, cierra la app.
  const AppPlugin = nativePlugin('App');
  if (AppPlugin && AppPlugin.addListener) {
    AppPlugin.addListener('backButton', () => {
      if (!$('#sheet').hidden) closeSheet();
      else if (state.tab !== 'new') showTab('new');
      else if (AppPlugin.exitApp) AppPlugin.exitApp();
    });
  }

  // ---------------------------------------------------------------- motor
  function renderEngine() {
    const banner = $('#banner');
    let text = '';
    let isErr = false;
    if (state.engineError) { text = state.engineError; isErr = true; }
    else if (state.updating) text = 'Actualizando el motor de descarga…';
    else if (!state.ready) text = 'Preparando el motor de descarga…';
    banner.hidden = !text;
    banner.classList.toggle('error', isErr);
    $('#bannerText').textContent = text;

    let sub;
    if (state.engineError) sub = 'No disponible';
    else if (state.updating) sub = 'Actualizando…';
    else if (!state.ready) sub = 'Preparando…';
    else sub = 'Listo · actualizado ' + ago(store.get('lastUpdate', 0));
    $('#engineText').textContent = sub;
    updateButtons();
  }

  function humanStatus(s) {
    const v = String(s || '').toUpperCase();
    if (v.includes('ALREADY')) return 'Ya tienes la última versión';
    if (v.includes('DONE')) return 'Motor actualizado';
    return 'Motor actualizado';
  }

  async function updateEngine(manual) {
    if (!YtDlp || state.updating || !state.ready) return;
    state.updating = true;
    renderEngine();
    try {
      const r = await YtDlp.updateEngine();
      store.set('lastUpdate', Date.now());
      if (manual) toast(humanStatus(r && r.status));
    } catch (e) {
      log('No se pudo actualizar: ' + (e && e.message ? e.message : e));
      if (manual) toast('No se pudo actualizar. Revisa tu conexión.');
    } finally {
      state.updating = false;
      renderEngine();
    }
  }
  $('#updateBtn').addEventListener('click', () => updateEngine(true));

  async function init() {
    YtDlp = nativePlugin('YtDlp');
    if (!YtDlp) {
      state.engineError = 'Esta app solo funciona instalada en Android.';
      renderEngine();
      return;
    }
    renderEngine();
    try {
      await YtDlp.prepare();
      state.ready = true;
      store.set('lastUpdate', store.get('lastUpdate', 0));
      renderEngine();
    } catch (e) {
      state.engineError = (e && e.message) || 'No se pudo iniciar el motor.';
      renderEngine();
      return;
    }
    // yt-dlp envejece rápido: se actualiza si pasaron más de 2 días.
    if (Date.now() - store.get('lastUpdate', 0) > 2 * 24 * 3600 * 1000) await updateEngine(false);
    checkShared();
  }

  // ------------------------------------------------ enlaces compartidos
  async function checkShared() {
    if (!YtDlp) return;
    try {
      const r = await YtDlp.getSharedText();
      const urls = extractUrls(r && r.text);
      if (urls.length) {
        const cur = extractUrls($('#urls').value);
        urls.forEach((u) => { if (!cur.includes(u)) cur.push(u); });
        $('#urls').value = cur.join('\n');
        showTab('new');
        syncField();
        toast(urls.length === 1 ? 'Enlace recibido' : urls.length + ' enlaces recibidos');
      }
    } catch (e) { /* nada compartido */ }
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden) checkShared(); });

  // ----------------------------------------------------------- formulario
  function syncField() {
    const ta = $('#urls');
    const n = extractUrls(ta.value).length;
    const hasText = ta.value.trim().length > 0;
    const tool = $('#pasteBtn');
    tool.classList.toggle('has-text', hasText);
    tool.setAttribute('aria-label', hasText ? 'Borrar texto' : 'Pegar enlace');
    tool.innerHTML = icon(hasText ? 'close' : 'paste');
    const btn = $('#startBtn');
    if (n === 0) btn.textContent = 'Descargar';
    else btn.textContent = 'Descargar ' + n + (n === 1 ? ' enlace' : ' enlaces');
    updateButtons();
  }
  $('#urls').addEventListener('input', syncField);

  $('#pasteBtn').addEventListener('click', async () => {
    const ta = $('#urls');
    if (ta.value.trim()) { ta.value = ''; syncField(); return; }
    try {
      const text = await navigator.clipboard.readText();
      const urls = extractUrls(text);
      if (!urls.length) { toast('No hay enlaces en el portapapeles'); return; }
      ta.value = urls.join('\n');
      syncField();
    } catch (e) {
      ta.focus();
      toast('Mantén presionado el cuadro para pegar');
    }
  });

  function applyFormat() {
    $$('[data-fmt]').forEach((b) => b.setAttribute('aria-checked', String(b.dataset.fmt === state.format)));
    $('#qualityRow').hidden = state.format === 'mp3';
  }
  $$('[data-fmt]').forEach((b) => b.addEventListener('click', () => {
    state.format = b.dataset.fmt;
    store.set('format', state.format);
    applyFormat();
  }));

  const quality = $('#quality');
  quality.value = store.get('quality', '1080');
  function syncQualityLabel() {
    const opt = quality.options[quality.selectedIndex];
    $('#qualityLabel').textContent = opt ? opt.textContent.replace(/\s*\(.*\)/, '') : '';
  }
  quality.addEventListener('change', () => { store.set('quality', quality.value); syncQualityLabel(); });
  syncQualityLabel();

  $('#skipHist').checked = store.get('skipHist', true);
  $('#skipHist').addEventListener('change', (e) => store.set('skipHist', e.target.checked));
  $('#allowList').checked = store.get('allowList', false);
  $('#allowList').addEventListener('change', (e) => store.set('allowList', e.target.checked));

  // ------------------------------------------------------------ historial
  function renderHistory() {
    const n = history.length;
    $('#histText').textContent = n + (n === 1 ? ' video' : ' videos');
    $('#clearHist').disabled = n === 0;
  }
  function addHistory(item) {
    history = history.filter((h) => h.key !== item.key);
    history.push({ key: item.key, url: item.url, title: item.title || '', date: Date.now() });
    if (history.length > 2000) history = history.slice(-2000);
    store.set('history', history);
    renderHistory();
  }
  $('#clearHist').addEventListener('click', () => {
    if (!history.length) return;
    if (!confirm('¿Borrar el historial de descargas?')) return;
    history = [];
    store.set('history', history);
    renderHistory();
    toast('Historial borrado');
  });

  // ----------------------------------------------------------------- cola
  const isActive = (i) => i.status === 'pending' || i.status === 'running';

  function updateButtons() {
    const n = extractUrls($('#urls').value).length;
    $('#startBtn').disabled = !state.ready || state.updating || n === 0;
    $('#updateBtn').disabled = !state.ready || state.updating || state.running;

    const active = state.queue.filter(isActive).length;
    const badge = $('#badge');
    badge.hidden = active === 0;
    badge.textContent = active;
    $('#cancelBar').hidden = active === 0;
    $('#clearDone').hidden = state.queue.length === 0 || state.queue.every(isActive);
  }

  function statusText(i) {
    switch (i.status) {
      case 'pending': return 'En espera';
      case 'running': {
        if (!(i.progress > 0)) return 'Preparando…';
        const eta = fmtEta(i.eta);
        return eta ? 'Faltan ' + eta : 'Descargando';
      }
      case 'done': return i.uri ? 'Listo · toca para abrir o eliminar' : 'Listo';
      case 'error': return i.error || 'Error desconocido';
      case 'cancelled': return 'Cancelada';
      case 'skipped': return 'Ya estaba en el historial';
      default: return '';
    }
  }

  function endControl(i) {
    if (isActive(i)) {
      if (i.status === 'running' && i.progress > 0) {
        return '<span class="dl-pct">' + Math.floor(i.progress) + '%</span>' +
          '<button class="dl-x" data-cancel="' + i.id + '" aria-label="Cancelar">' + icon('close') + '</button>';
      }
      return '<button class="dl-x" data-cancel="' + i.id + '" aria-label="Cancelar">' + icon('close') + '</button>';
    }
    if (i.status === 'done') return '<span class="dl-end">' + icon('check') + '</span>';
    if (i.status === 'error') return '<span class="dl-end">' + icon('alert') + '</span>';
    if (i.status === 'skipped') return '<span class="dl-end">' + icon('skip') + '</span>';
    return '';
  }

  function rowHtml(i) {
    const p = i.status === 'done' ? 100 : Math.max(0, Math.min(100, i.progress || 0));
    const open = i.status === 'done' && i.uri ? ' data-open="' + i.id + '" role="button" tabindex="0"' : '';
    return '<li class="dl ' + i.status + '" data-id="' + i.id + '"' + open + ' style="--p:' + p + '%">' +
      '<span class="dl-ico">' + icon(i.opts.format === 'mp3' ? 'music' : 'film') + '</span>' +
      '<div class="dl-body"><span class="dl-t">' + esc(i.title || i.url) + '</span>' +
      '<span class="dl-s">' + esc(statusText(i)) + '</span></div>' +
      endControl(i) + '</li>';
  }

  // Reconstruye toda la lista (cambios de estado).
  function renderQueue() {
    $('#queue').innerHTML = state.queue.slice().reverse().map(rowHtml).join('');
    $('#empty').hidden = state.queue.length > 0;
    updateButtons();
  }

  // Solo actualiza la fila que avanza, para no interrumpir toques.
  function updateRow(item) {
    const li = $('#queue [data-id="' + item.id + '"]');
    if (!li) return renderQueue();
    const p = Math.max(0, Math.min(100, item.progress || 0));
    li.style.setProperty('--p', p + '%');
    li.querySelector('.dl-t').textContent = item.title || item.url;
    li.querySelector('.dl-s').textContent = statusText(item);
    const pct = li.querySelector('.dl-pct');
    if (pct) pct.textContent = Math.floor(item.progress) + '%';
    else if (item.progress > 0) renderQueue();
  }

  // Agrega enlaces a la cola con los ajustes actuales y arranca las descargas.
  function enqueueUrls(urls) {
    const opts = {
      format: state.format,
      maxHeight: parseInt(quality.value, 10) || 0,
      playlist: $('#allowList').checked,
    };
    const skip = $('#skipHist').checked;
    let added = 0;

    urls.forEach((entry) => {
      // Cada entrada es un enlace o {url, format, maxHeight, title} elegidos en el navegador.
      const isObj = entry && typeof entry === 'object';
      const url = isObj ? entry.url : entry;
      const o = isObj
        ? Object.assign({}, opts, {
            format: entry.format || opts.format,
            maxHeight: entry.maxHeight != null ? entry.maxHeight : opts.maxHeight,
          })
        : opts;
      const key = videoKey(url);
      const base = {
        id: 'dl_' + Date.now() + '_' + (seq++), url, key, progress: 0, eta: 0,
        title: (isObj && entry.title) || '', logIdx: 0, opts: o,
      };
      const seen = skip && history.find((h) => h.key === key);
      if (seen) {
        state.queue.push(Object.assign(base, { status: 'skipped', title: seen.title || '' }));
      } else {
        state.queue.push(Object.assign(base, { status: 'pending' }));
        added++;
      }
    });

    renderQueue();
    showTab('dl');
    if (added) runQueue();
    else toast(urls.length === 1 ? 'Ese video ya estaba descargado' : 'Todos esos videos ya estaban descargados');
    return added;
  }

  $('#startBtn').addEventListener('click', () => {
    const urls = extractUrls($('#urls').value);
    if (!urls.length) return;
    $('#urls').value = '';
    syncField();
    enqueueUrls(urls);
  });

  // -------------------------------------------------- navegador por sitio
  const SITES = [
    { id: 'youtube', name: 'YouTube', letter: 'Y', h: 2 },
    { id: 'facebook', name: 'Facebook', letter: 'f', h: 214 },
    { id: 'tiktok', name: 'TikTok', letter: 'T', h: 172 },
    { id: 'instagram', name: 'Instagram', letter: 'I', h: 322 },
    { id: 'x', name: 'X', letter: 'X', h: 220, s: 10 },
    { id: 'vimeo', name: 'Vimeo', letter: 'V', h: 196 },
  ];

  // Si existe www/icons/<sitio>.png se muestra encima de la letra.
  $('#sites').innerHTML = SITES.map((s) =>
    '<button class="site" type="button" role="listitem" data-site="' + s.id + '">' +
    '<span class="site-ico" style="--h:' + s.h + (s.s != null ? ';--s:' + s.s + '%' : '') + '">' +
    '<b>' + s.letter + '</b><img src="icons/' + s.id + '.png" alt="" onerror="this.remove()"></span>' +
    '<span class="site-l">' + s.name + '</span></button>'
  ).join('');

  $('#sites').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-site]');
    if (!btn) return;
    if (!YtDlp) { toast('Solo está disponible en la app de Android'); return; }
    try {
      await YtDlp.openBrowser({ site: btn.dataset.site });
    } catch (err) {
      toast((err && err.message) || 'No se pudo abrir el navegador');
    }
  });

  // Recoge los videos agregados desde el navegador al volver a la app.
  async function checkPending() {
    if (!YtDlp || !state.ready) return;
    try {
      const r = await YtDlp.takePending();
      const items = (r && r.items) || [];
      if (items.length) enqueueUrls(items);
    } catch (e) { /* nada pendiente */ }
  }
  setInterval(() => {
    if (document.hidden) return;
    checkPending();
    checkShared();
  }, 1500);

  async function runQueue() {
    if (state.running) return;
    state.running = true;
    updateButtons();
    try {
      for (;;) {
        const item = state.queue.find((i) => i.status === 'pending');
        if (!item) break;
        await downloadItem(item);
      }
    } finally {
      state.running = false;
      updateButtons();
    }
  }

  async function downloadItem(item) {
    item.status = 'running';
    renderQueue();
    log('▶ ' + item.url);

    const timer = setInterval(async () => {
      try {
        const s = await YtDlp.getProgress({ id: item.id, since: item.logIdx });
        item.progress = s.progress || 0;
        item.eta = s.eta || 0;
        (s.logs || []).forEach((l) => {
          log(l);
          const t = parseTitle(l);
          if (t) item.title = t;
        });
        item.logIdx = s.logCount || item.logIdx;
        if (item.status === 'running') updateRow(item);
      } catch (e) { /* siguiente ciclo */ }
    }, 500);

    try {
      const res = await YtDlp.download({
        id: item.id,
        url: item.url,
        format: item.opts.format,
        maxHeight: item.opts.maxHeight,
        playlist: item.opts.playlist,
      });
      if (res && res.cancelled) {
        item.status = 'cancelled';
      } else {
        item.status = 'done';
        item.progress = 100;
        item.files = (res && res.files) || [];
        const outs = (res && res.items) || [];
        if (outs[0]) applyOut(item, outs[0]);
        else if (!item.title && item.files[0]) item.title = item.files[0].split('/').pop().replace(/\.[^.]+$/, '');
        // Listas de reproducción: cada archivo adicional queda como su propia fila.
        outs.slice(1).forEach((o) => {
          const extra = {
            id: 'dl_' + Date.now() + '_' + (seq++), url: item.url, key: item.key + '#' + o.name,
            progress: 100, eta: 0, logIdx: 0, opts: item.opts, status: 'done', files: [o.path],
          };
          applyOut(extra, o);
          state.queue.push(extra);
        });
        addHistory(item);
        persistLibrary();
        log('Listo: ' + (item.title || item.url));
      }
    } catch (e) {
      item.status = 'error';
      item.error = (e && e.message) || String(e);
      log('Error: ' + item.error);
    } finally {
      clearInterval(timer);
      renderQueue();
    }
  }

  async function cancelItem(id) {
    const item = state.queue.find((i) => i.id === id);
    if (!item) return;
    if (item.status === 'pending') {
      item.status = 'cancelled';
    } else if (item.status === 'running') {
      try { await YtDlp.cancel({ id }); } catch (e) { /* ya terminó */ }
    }
    renderQueue();
  }

  $('#queue').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-cancel]');
    if (btn) { cancelItem(btn.dataset.cancel); return; }
    const row = e.target.closest('[data-open]');
    if (row) openSheet(state.queue.find((i) => i.id === row.dataset.open));
  });
  $('#queue').addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    const row = e.target.closest('[data-open]');
    if (row) { e.preventDefault(); openSheet(state.queue.find((i) => i.id === row.dataset.open)); }
  });

  $('#cancelAllBtn').addEventListener('click', async () => {
    state.queue.forEach((i) => { if (i.status === 'pending') i.status = 'cancelled'; });
    const running = state.queue.find((i) => i.status === 'running');
    renderQueue();
    if (running) await cancelItem(running.id);
  });

  $('#clearDone').addEventListener('click', () => {
    state.queue = state.queue.filter(isActive);
    persistLibrary();
    renderQueue();
  });

  // ------------------------------------------- archivos descargados
  function applyOut(it, o) {
    it.uri = o.uri;
    it.mime = o.mime;
    it.path = o.path;
    it.files = [o.path];
    const name = String(o.name || '').replace(/\.[^.]+$/, '');
    if (name) it.title = name;
  }

  // Las descargas terminadas se recuerdan para poder abrirlas o borrarlas después.
  function persistLibrary() {
    const list = state.queue
      .filter((i) => i.status === 'done' && i.uri)
      .slice(-60)
      .map((i) => ({
        id: i.id, url: i.url, key: i.key, title: i.title,
        uri: i.uri, mime: i.mime, path: i.path, format: i.opts && i.opts.format,
      }));
    store.set('library', list);
  }

  function restoreLibrary() {
    store.get('library', []).forEach((l) => {
      if (!l || !l.uri) return;
      state.queue.push({
        id: l.id, url: l.url || '', key: l.key || '', title: l.title || '',
        uri: l.uri, mime: l.mime, path: l.path, files: [l.path],
        status: 'done', progress: 100, eta: 0, logIdx: 0,
        opts: { format: l.format || 'mp4' },
      });
    });
  }

  function removeItem(item) {
    state.queue = state.queue.filter((i) => i !== item);
    persistLibrary();
    renderQueue();
  }

  const sheet = { item: null, step: 'main' };

  function openSheet(item) {
    if (!item) return;
    sheet.item = item;
    sheet.step = 'main';
    renderSheet();
    $('#sheet').hidden = false;
  }
  function closeSheet() {
    $('#sheet').hidden = true;
    sheet.item = null;
  }
  function renderSheet() {
    const it = sheet.item;
    if (!it) return;
    $('#sheetTitle').textContent = it.title || 'Video';
    const acts = $('#sheetActions');
    if (sheet.step === 'main') {
      $('#sheetSub').textContent = it.path || '';
      acts.innerHTML =
        '<button class="primary" type="button" data-act="play">Reproducir en la app</button>' +
        '<button class="primary quiet" type="button" data-act="open">Abrir con otra app</button>' +
        '<button class="primary danger" type="button" data-act="ask">Eliminar del teléfono</button>';
    } else {
      $('#sheetSub').textContent = 'Se borrará el archivo del teléfono. Esta acción no se puede deshacer.';
      acts.innerHTML =
        '<button class="primary solid-danger" type="button" data-act="del">Eliminar</button>' +
        '<button class="primary quiet" type="button" data-act="back">Cancelar</button>';
    }
  }

  $('#scrim').addEventListener('click', closeSheet);
  $('#sheetActions').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-act]');
    const it = sheet.item;
    if (!btn || !it) return;
    const act = btn.dataset.act;

    if (act === 'ask') { sheet.step = 'confirm'; renderSheet(); return; }
    if (act === 'back') { sheet.step = 'main'; renderSheet(); return; }

    if (act === 'play') {
      closeSheet();
      try {
        await YtDlp.playMedia({
          kind: it.opts && it.opts.format === 'mp3' ? 'audio' : 'video',
          index: 0,
          items: [{ uri: it.uri, title: it.title }],
        });
      } catch (err) {
        toast((err && err.message) || 'No se pudo abrir el reproductor');
      }
      return;
    }

    if (act === 'open') {
      try {
        await YtDlp.openFile({ uri: it.uri, mime: it.mime });
        closeSheet();
      } catch (err) {
        const msg = (err && err.message) || 'No se pudo abrir el archivo';
        toast(msg);
        if (/ya no existe/i.test(msg)) { closeSheet(); removeItem(it); }
      }
      return;
    }

    if (act === 'del') {
      try {
        const r = await YtDlp.deleteFile({ uri: it.uri });
        closeSheet();
        removeItem(it);
        history = history.filter((h) => h.key !== it.key);
        store.set('history', history);
        renderHistory();
        toast(r && r.deleted === false ? 'El archivo ya no estaba en el teléfono' : 'Video eliminado');
      } catch (err) {
        toast((err && err.message) || 'No se pudo eliminar');
        sheet.step = 'main';
        renderSheet();
      }
    }
  });

  // ------------------------------------------------------------ biblioteca
  const lib = {
    kind: store.get('libKind', 'video'),
    all: { video: [], audio: [] },
    view: [],
    folder: '',
    q: '',
    shown: 0,
    loadedAt: 0,
    loading: false,
  };
  const PAGE = 120;

  function fmtDur(ms) {
    const s = Math.round((ms || 0) / 1000);
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const x = s % 60;
    return h > 0
      ? h + ':' + String(m).padStart(2, '0') + ':' + String(x).padStart(2, '0')
      : m + ':' + String(x).padStart(2, '0');
  }
  function fmtSize(b) {
    if (!b) return '';
    return b >= 1e9 ? (b / 1e9).toFixed(1) + ' GB' : Math.max(1, Math.round(b / 1e6)) + ' MB';
  }
  const cleanArtist = (a) => (a && a !== '<unknown>' ? a : '');

  function showGate(title, text, canAsk) {
    $('#libGate').hidden = false;
    $('#libMain').hidden = true;
    $('#libGateT').textContent = title;
    $('#libGateS').textContent = text;
    $('#libGrant').hidden = !canAsk;
  }

  async function libOpen() {
    if (!YtDlp) {
      showGate('Solo en Android', 'La biblioteca funciona dentro de la app instalada en el teléfono.', false);
      return;
    }
    let granted = false;
    try {
      const r = await YtDlp.mediaAccess();
      granted = !!(r && r.granted);
    } catch (e) { /* sin permiso */ }
    if (!granted) {
      showGate(
        'Da acceso a tus archivos',
        'Para mostrar tus videos y tu música, Android necesita que lo permitas una vez.',
        true
      );
      return;
    }
    $('#libGate').hidden = true;
    $('#libMain').hidden = false;
    if (Date.now() - lib.loadedAt > 30000) loadLibrary();
  }

  $('#libGrant').addEventListener('click', async () => {
    try {
      const r = await YtDlp.requestMediaAccess();
      if (r && r.granted) libOpen();
      else toast('Sin ese permiso no puedo mostrar tus archivos');
    } catch (e) {
      toast('No se pudo pedir el permiso');
    }
  });

  async function loadLibrary() {
    if (lib.loading) return;
    lib.loading = true;
    $('#libStatus').hidden = false;
    try {
      const r = await YtDlp.listMedia();
      if (r && r.granted === false) { lib.loading = false; $('#libStatus').hidden = true; libOpen(); return; }
      lib.all.video = (r && r.videos) || [];
      lib.all.audio = (r && r.audios) || [];
      lib.loadedAt = Date.now();
    } catch (e) {
      toast((e && e.message) || 'No se pudo leer la biblioteca');
    } finally {
      lib.loading = false;
      $('#libStatus').hidden = true;
      refreshLib();
    }
  }

  function refreshLib() {
    // pestañas con conteo
    $$('[data-kind]').forEach((b) => {
      const k = b.dataset.kind;
      b.textContent = (k === 'video' ? 'Videos' : 'Audio') + ' (' + lib.all[k].length + ')';
      b.setAttribute('aria-checked', String(k === lib.kind));
    });
    // carpetas del tipo actual
    const counts = new Map();
    lib.all[lib.kind].forEach((x) => counts.set(x.folder, (counts.get(x.folder) || 0) + 1));
    if (lib.folder && !counts.has(lib.folder)) lib.folder = '';
    const sel = $('#folderSel');
    const folders = Array.from(counts.entries()).sort((a, b) => b[1] - a[1]);
    sel.innerHTML = '<option value="">Todas las carpetas (' + lib.all[lib.kind].length + ')</option>' +
      folders.map((f) => '<option value="' + esc(f[0]) + '">' + esc(f[0]) + ' (' + f[1] + ')</option>').join('');
    sel.value = lib.folder;
    $('#folderLabel').textContent = lib.folder || 'Todas';
    applyLibFilter();
  }

  function applyLibFilter() {
    const q = lib.q.trim().toLowerCase();
    lib.view = lib.all[lib.kind].filter((x) =>
      (!lib.folder || x.folder === lib.folder) &&
      (!q || (x.title + ' ' + x.name + ' ' + (x.artist || '')).toLowerCase().includes(q))
    );
    renderLib(false);
  }

  function mediaRow(x, i) {
    if (lib.kind === 'video') {
      return '<li class="m" data-i="' + i + '" role="button" tabindex="0">' +
        '<span class="m-th"><img data-uri="' + esc(x.uri) + '" alt="">' +
        '<span class="m-dur">' + fmtDur(x.duration) + '</span></span>' +
        '<div class="m-body"><span class="m-t">' + esc(x.title) + '</span>' +
        '<span class="m-s">' + esc([x.folder, fmtSize(x.size)].filter(Boolean).join(' · ')) + '</span></div></li>';
    }
    const artist = cleanArtist(x.artist);
    return '<li class="m" data-i="' + i + '" role="button" tabindex="0">' +
      '<span class="m-ic">' + icon('music') + '</span>' +
      '<div class="m-body"><span class="m-t">' + esc(x.title) + '</span>' +
      '<span class="m-s">' + esc([artist, x.folder, fmtDur(x.duration)].filter(Boolean).join(' · ')) + '</span></div></li>';
  }

  function renderLib(more) {
    const ul = $('#libList');
    if (!more) { lib.shown = 0; ul.innerHTML = ''; }
    const next = lib.view.slice(lib.shown, lib.shown + PAGE);
    ul.insertAdjacentHTML('beforeend', next.map((x, k) => mediaRow(x, lib.shown + k)).join(''));
    lib.shown += next.length;
    $('#libMore').hidden = lib.shown >= lib.view.length;
    $('#libMore').textContent = 'Mostrar más (' + (lib.view.length - lib.shown) + ')';
    const empty = lib.view.length === 0 && !lib.loading;
    $('#libEmpty').hidden = !empty;
    $('#libEmpty').textContent = lib.q || lib.folder
      ? 'No hay resultados con ese filtro.'
      : (lib.kind === 'video' ? 'No hay videos en el teléfono.' : 'No hay audios en el teléfono.');
    if (lib.kind === 'video') {
      ul.querySelectorAll('img[data-uri]:not([data-obs])').forEach((img) => {
        img.dataset.obs = '1';
        thumbObserver.observe(img);
      });
    }
  }

  // Miniaturas: se piden solo cuando la fila aparece en pantalla.
  const thumbCache = new Map();
  const thumbQueue = [];
  let thumbBusy = 0;
  const thumbObserver = new IntersectionObserver((entries) => {
    entries.forEach((en) => {
      if (!en.isIntersecting) return;
      thumbObserver.unobserve(en.target);
      wantThumb(en.target);
    });
  }, { rootMargin: '200px' });

  function wantThumb(img) {
    const uri = img.dataset.uri;
    if (thumbCache.has(uri)) { img.src = thumbCache.get(uri); return; }
    thumbQueue.push(img);
    pumpThumbs();
  }
  function pumpThumbs() {
    while (thumbBusy < 3 && thumbQueue.length) {
      const img = thumbQueue.shift();
      if (!img.isConnected) continue;
      const uri = img.dataset.uri;
      thumbBusy++;
      YtDlp.getThumbnail({ uri }).then((r) => {
        if (r && r.data) {
          const src = 'data:image/jpeg;base64,' + r.data;
          thumbCache.set(uri, src);
          if (thumbCache.size > 400) thumbCache.delete(thumbCache.keys().next().value);
          img.src = src;
        }
      }).catch(() => {}).finally(() => { thumbBusy--; pumpThumbs(); });
    }
  }

  async function playAt(i) {
    const start = Math.max(0, i - 30);
    const slice = lib.view.slice(start, start + 300);
    try {
      await YtDlp.playMedia({
        kind: lib.kind,
        index: i - start,
        items: slice.map((x) => ({ uri: x.uri, title: x.title, artist: cleanArtist(x.artist) })),
      });
    } catch (e) {
      toast((e && e.message) || 'No se pudo abrir el reproductor');
    }
  }

  $('#libList').addEventListener('click', (e) => {
    const li = e.target.closest('[data-i]');
    if (li) playAt(parseInt(li.dataset.i, 10));
  });
  $('#libList').addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    const li = e.target.closest('[data-i]');
    if (li) { e.preventDefault(); playAt(parseInt(li.dataset.i, 10)); }
  });
  $('#libMore').addEventListener('click', () => renderLib(true));

  $$('[data-kind]').forEach((b) => b.addEventListener('click', () => {
    lib.kind = b.dataset.kind;
    store.set('libKind', lib.kind);
    lib.folder = '';
    refreshLib();
  }));
  $('#folderSel').addEventListener('change', (e) => {
    lib.folder = e.target.value;
    $('#folderLabel').textContent = lib.folder || 'Todas';
    applyLibFilter();
  });
  let searchTimer = null;
  $('#libSearch').addEventListener('input', (e) => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => { lib.q = e.target.value; applyLibFilter(); }, 180);
  });

  // -------------------------------------------------------------- arranque
  applyTheme();
  applyFormat();
  restoreLibrary();
  renderHistory();
  renderEngine();
  renderQueue();
  syncField();
  init();
})();
