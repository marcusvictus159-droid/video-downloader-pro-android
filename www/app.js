(() => {
  'use strict';

  const $ = (s) => document.querySelector(s);

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

  // ------------------------------------------------------------------ estado
  const state = {
    format: store.get('format', 'mp4'),
    queue: [],
    running: false,
    ready: false,
    updating: false,
  };
  let history = store.get('history', []);
  let YtDlp = null;
  let seq = 0;

  // ---------------------------------------------------------------- utilidades
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));

  let toastTimer = null;
  function toast(msg) {
    const t = $('#toast');
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, 2800);
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
    const m = Math.floor(sec / 60);
    const s = Math.floor(sec % 60);
    return m + ':' + String(s).padStart(2, '0');
  }

  function extractUrls(text) {
    const found = (text || '').match(/https?:\/\/[^\s"'<>]+/gi) || [];
    return Array.from(new Set(found.map((u) => u.replace(/[),.;]+$/, ''))));
  }

  // Clave estable por video para el historial (YouTube por id, el resto por URL).
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

  // -------------------------------------------------------------------- tema
  function applyTheme() {
    const t = store.get('theme', 'auto');
    if (t === 'auto') document.documentElement.removeAttribute('data-theme');
    else document.documentElement.setAttribute('data-theme', t);
    $('#themeBtn').textContent = t === 'light' ? '☀️' : t === 'dark' ? '🌙' : '🌓';
  }
  $('#themeBtn').addEventListener('click', () => {
    const order = ['auto', 'light', 'dark'];
    const cur = store.get('theme', 'auto');
    store.set('theme', order[(order.indexOf(cur) + 1) % order.length]);
    applyTheme();
  });

  // ----------------------------------------------------------------- motor
  function setEngine(kind, text) {
    $('#engineDot').className = 'dot ' + kind;
    $('#engineText').textContent = text;
  }

  function humanStatus(s) {
    const v = String(s || '').toUpperCase();
    if (v.includes('ALREADY')) return 'Ya tienes la última versión';
    if (v.includes('DONE')) return 'Motor actualizado';
    return s ? String(s) : 'Listo';
  }

  async function updateEngine(manual) {
    if (!YtDlp || state.updating) return;
    state.updating = true;
    setEngine('busy', 'Actualizando motor…');
    updateButtons();
    try {
      const r = await YtDlp.updateEngine();
      store.set('lastUpdate', Date.now());
      setEngine('ok', 'Motor listo · ' + humanStatus(r && r.status));
      if (manual) toast(humanStatus(r && r.status));
    } catch (e) {
      setEngine('ok', 'Motor listo (sin actualizar)');
      log('⚠️ ' + (e && e.message ? e.message : e));
      if (manual) toast('No se pudo actualizar. Revisa tu internet.');
    } finally {
      state.updating = false;
      updateButtons();
    }
  }

  $('#updateBtn').addEventListener('click', () => updateEngine(true));

  function getPlugin() {
    const c = window.Capacitor;
    if (!c) return null;
    if (c.Plugins && c.Plugins.YtDlp) return c.Plugins.YtDlp;
    if (typeof c.registerPlugin === 'function') return c.registerPlugin('YtDlp');
    return null;
  }

  async function init() {
    YtDlp = getPlugin();
    if (!YtDlp) {
      setEngine('error', 'Esta app solo funciona instalada en Android');
      return;
    }
    try {
      await YtDlp.prepare();
      state.ready = true;
      setEngine('ok', 'Motor listo');
      updateButtons();
    } catch (e) {
      setEngine('error', (e && e.message) || 'No se pudo iniciar el motor');
      return;
    }
    // El yt-dlp incluido en la app envejece rápido: se actualiza cada 2 días.
    const last = store.get('lastUpdate', 0);
    if (Date.now() - last > 2 * 24 * 3600 * 1000) await updateEngine(false);
    checkShared();
  }

  // -------------------------------------------------- enlaces compartidos
  async function checkShared() {
    if (!YtDlp) return;
    try {
      const r = await YtDlp.getSharedText();
      const urls = extractUrls(r && r.text);
      if (urls.length) {
        const ta = $('#urls');
        const cur = extractUrls(ta.value);
        urls.forEach((u) => { if (!cur.includes(u)) cur.push(u); });
        ta.value = cur.join('\n');
        toast('Enlace recibido');
      }
    } catch (e) { /* nada compartido */ }
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden) checkShared(); });

  // ------------------------------------------------------------- formulario
  function applyFormat() {
    document.querySelectorAll('.seg-btn').forEach((b) => {
      b.classList.toggle('active', b.dataset.fmt === state.format);
    });
    $('#qualityRow').hidden = state.format === 'mp3';
  }
  document.querySelectorAll('.seg-btn').forEach((b) => {
    b.addEventListener('click', () => {
      state.format = b.dataset.fmt;
      store.set('format', state.format);
      applyFormat();
    });
  });

  $('#quality').value = store.get('quality', '1080');
  $('#quality').addEventListener('change', (e) => store.set('quality', e.target.value));
  $('#skipHist').checked = store.get('skipHist', true);
  $('#skipHist').addEventListener('change', (e) => store.set('skipHist', e.target.checked));
  $('#allowList').checked = store.get('allowList', false);
  $('#allowList').addEventListener('change', (e) => store.set('allowList', e.target.checked));

  $('#clearBtn').addEventListener('click', () => { $('#urls').value = ''; });
  $('#pasteBtn').addEventListener('click', async () => {
    try {
      const text = await navigator.clipboard.readText();
      const urls = extractUrls(text);
      if (!urls.length) return toast('El portapapeles no tiene enlaces');
      const cur = extractUrls($('#urls').value);
      urls.forEach((u) => { if (!cur.includes(u)) cur.push(u); });
      $('#urls').value = cur.join('\n');
    } catch (e) {
      toast('Mantén presionado en el cuadro para pegar');
    }
  });

  // --------------------------------------------------------------- historial
  function updateHistCount() { $('#histCount').textContent = history.length; }
  function addHistory(item) {
    history = history.filter((h) => h.key !== item.key);
    history.push({ key: item.key, url: item.url, title: item.title || '', date: Date.now() });
    if (history.length > 2000) history = history.slice(-2000);
    store.set('history', history);
    updateHistCount();
  }
  $('#clearHist').addEventListener('click', () => {
    if (!history.length) return toast('El historial ya está vacío');
    if (!confirm('¿Borrar el historial de descargas?')) return;
    history = [];
    store.set('history', history);
    updateHistCount();
    toast('Historial borrado');
  });

  // -------------------------------------------------------------------- cola
  function updateButtons() {
    const hasPending = state.queue.some((i) => i.status === 'pending' || i.status === 'running');
    $('#startBtn').disabled = !state.ready || state.updating;
    $('#updateBtn').disabled = !state.ready || state.updating || state.running;
    $('#cancelAllBtn').hidden = !hasPending;
  }

  function statusText(i) {
    switch (i.status) {
      case 'pending': return 'En espera…';
      case 'running': {
        const pct = i.progress > 0 ? i.progress.toFixed(1) + '%' : 'Preparando…';
        const eta = fmtEta(i.eta);
        return pct + (eta ? ' · faltan ' + eta : '');
      }
      case 'done': return '✅ Guardado en ' + (i.files && i.files[0] ? i.files[0] : 'Descargas/VideoDownloaderPro');
      case 'error': return '❌ ' + (i.error || 'Error desconocido');
      case 'cancelled': return '🚫 Cancelado';
      case 'skipped': return '⏭️ Ya estaba en el historial';
      default: return '';
    }
  }

  function renderQueue() {
    $('#queueCard').hidden = state.queue.length === 0;
    $('#queue').innerHTML = state.queue.map((i) => {
      const cancelable = i.status === 'pending' || i.status === 'running';
      const pct = i.status === 'done' ? 100 : Math.max(0, Math.min(100, i.progress || 0));
      const indeterminate = i.status === 'running' && !(i.progress > 0);
      return '<li class="q ' + i.status + '">' +
        '<div class="q-top"><span class="q-title">' + esc(i.title || i.url) + '</span>' +
        (cancelable ? '<button class="x" data-cancel="' + i.id + '" aria-label="Cancelar">✕</button>' : '') +
        '</div>' +
        '<div class="bar' + (indeterminate ? ' ind' : '') + '"><i style="width:' + pct + '%"></i></div>' +
        '<div class="q-status">' + esc(statusText(i)) + '</div></li>';
    }).join('');
    updateButtons();
  }

  $('#startBtn').addEventListener('click', () => {
    const urls = extractUrls($('#urls').value);
    if (!urls.length) return toast('Pega al menos un enlace válido');

    const opts = {
      format: state.format,
      maxHeight: parseInt($('#quality').value, 10) || 0,
      playlist: $('#allowList').checked,
    };
    const skip = $('#skipHist').checked;
    let added = 0;

    urls.forEach((url) => {
      const key = videoKey(url);
      const base = { id: 'dl_' + Date.now() + '_' + (seq++), url, key, progress: 0, eta: 0, title: '', logIdx: 0, opts };
      if (skip && history.some((h) => h.key === key)) {
        const h = history.find((x) => x.key === key);
        state.queue.push(Object.assign(base, { status: 'skipped', title: (h && h.title) || '' }));
      } else {
        state.queue.push(Object.assign(base, { status: 'pending' }));
        added++;
      }
    });

    $('#urls').value = '';
    renderQueue();
    if (added) runQueue();
    else toast('Todos ya estaban descargados');
  });

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
        if (item.status === 'running') renderQueue();
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
        if (!item.title && item.files[0]) item.title = item.files[0].split('/').pop().replace(/\.[^.]+$/, '');
        addHistory(item);
        log('✅ Listo: ' + (item.title || item.url));
      }
    } catch (e) {
      item.status = 'error';
      item.error = (e && e.message) || String(e);
      log('❌ ' + item.error);
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
    if (btn) cancelItem(btn.dataset.cancel);
  });

  $('#cancelAllBtn').addEventListener('click', async () => {
    for (const i of state.queue) {
      if (i.status === 'pending') i.status = 'cancelled';
    }
    const running = state.queue.find((i) => i.status === 'running');
    renderQueue();
    if (running) await cancelItem(running.id);
    toast('Descargas canceladas');
  });

  // ----------------------------------------------------------------- arranque
  applyTheme();
  applyFormat();
  updateHistCount();
  updateButtons();
  init();
})();
