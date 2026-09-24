'use strict';
const $ = id => document.getElementById(id);

const paths = {
  play: '<path d="m8 5 11 7-11 7Z" fill="currentColor" stroke="none"/>',
  pause: '<path d="M7 5h3v14H7zM14 5h3v14h-3z" fill="currentColor" stroke="none"/>',
  next: '<path d="m6 5 9 7-9 7Z" fill="currentColor" stroke="none"/><path d="M18 5v14"/>',
  previous: '<path d="m18 5-9 7 9 7Z" fill="currentColor" stroke="none"/><path d="M6 5v14"/>',
  music: '<path d="M9 18V6l11-2v12"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/>',
  image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="1.6"/><path d="m21 16-5-5-8 8"/>',
  folderPlus: '<path d="M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11H3Z"/><path d="M12 10v6m-3-3h6"/>',
  power: '<path d="M12 2v10M6.3 5.3a9 9 0 1 0 11.4 0"/>',
  star: '<path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9Z"/>',
  wifi: '<path d="M2 8.8a16 16 0 0 1 20 0M5.3 12.2a11 11 0 0 1 13.4 0M8.6 15.6a6 6 0 0 1 6.8 0"/><circle cx="12" cy="19" r="1" fill="currentColor" stroke="none"/>',
  battery: '<rect x="2" y="7" width="17" height="10" rx="2"/><path d="M22 10.5v3"/>',
  bolt: '<path d="M11 4 5 13h5l-1 7 6-9h-5Z" fill="currentColor" stroke="none"/>',
  settings: '<path d="m9.5 3-.7 2.2-2 .9-2.2-.5-2.4 4.1 1.5 1.7v2.3l-1.5 1.7 2.4 4.1 2.2-.5 2 .9.7 2.1h5l.7-2.1 2-.9 2.2.5 2.4-4.1-1.5-1.7v-2.3l1.5-1.7-2.4-4.1-2.2.5-2-.9L14.5 3Z"/><circle cx="12" cy="12" r="3"/>',
  refresh: '<path d="M20 7v5h-5M4 17v-5h5M19 12a7 7 0 0 0-12-5L4 10m1 2a7 7 0 0 0 12 5l3-3"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>'
};
const icon = (name, filled = false) =>
  `<svg viewBox="0 0 24 24" aria-hidden="true" fill="${filled ? 'currentColor' : 'none'}" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${paths[name] || paths.play}</svg>`;

// ---- state ----
let state = { games: [], apps: [], folders: [], scanning: false, hasFolder: false, wallpaper: '', mediaAccess: false };
let media = { enabled: false, active: false };
let selected = '', page = 'home', favoriteOnly = false;
let signature = '', appSignature = '', lastScanMessage = '', toastTimer;

const prefs = {
  get(key, fallback) { try { const v = localStorage.getItem(key); return v === null ? fallback : JSON.parse(v); } catch (e) { return fallback; } },
  set(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { } }
};
let backgroundMode = prefs.get('background', 'art');
let androidGames = prefs.get('androidGames', true);

const native = (method, ...args) => {
  if (window.Deck && typeof Deck[method] === 'function') Deck[method](...args);
  else notify('Available in the Android app.');
};

function notify(message) {
  $('toast').textContent = message;
  $('toast').classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $('toast').classList.remove('show'), 2500);
}

// Art files come from app storage; a version query only busts the WebView cache.
function artUrl(name) {
  return name && /^[a-zA-Z0-9_-]+\.(jpg|png)(\?v=\d+)?$/.test(name) ? '/art/' + name : '';
}
// Covers at tile size: native serves a 400 px copy of "<key>-cover.jpg" as "<key>-cover-t.jpg".
function thumbUrl(name) {
  return artUrl(name && name.replace(/^([a-zA-Z0-9_]+-(?:cover|box))\.jpg/, '$1-t.jpg'));
}

// PSP games and installed Android games share one library shape. The list is rebuilt only when
// a new library arrives (or the Android-games switch flips): current() and friends call it often.
let allCache = null, allKey = '';
function allGames() {
  const key = signature + '|' + androidGames;
  if (allCache && allKey === key && signature) return allCache.slice();
  const psp = state.games.filter(g => !g.alt && !g.hidden).map(g => ({ ...g, android: false }));
  const apps = androidGames ? state.apps
    .map(a => ({ ...a, android: true }))
    .filter(a => a.game && !isEmulator(a)) : [];
  allCache = psp.concat(apps);
  allKey = key;
  return allCache.slice();
}
// Emulators never appear in the game library; they stay reachable in Apps and Settings.
const isEmulator = g => g.android && (state.emulatorPackages || []).includes(g.package);
const systemOf = g => (state.systems || []).find(s => s.id === (g.system || 'psp'));
function homeGames() { return allGames(); }
function byPlayed(a, b) {
  return (b.plays || 0) - (a.plays || 0) || (b.lastPlayed || 0) - (a.lastPlayed || 0) || a.title.localeCompare(b.title);
}
function current() {
  return allGames().find(g => g.id === selected) || homeGames().sort(byPlayed)[0];
}
function playGame(id) {
  selected = id;
  renderHome();
  native('play', id);
}

// ---- navigation ----
function goPage(next) {
  page = next;
  document.body.dataset.page = next;
  document.querySelectorAll('.page').forEach(el => el.classList.toggle('active', el.id === next));
  document.querySelectorAll('nav button').forEach(el => {
    const active = el.dataset.page === next;
    el.classList.toggle('active', active);
    if (active) el.setAttribute('aria-current', 'page'); else el.removeAttribute('aria-current');
  });
  $('settingsButton').classList.toggle('active', next === 'settings');
  closeGameMenu();
  if (next === 'library') renderLibrary();
  if (next === 'home') renderHome();
  if (next === 'settings') renderSettings();
  schedulePerformancePolling();
  updatePreview();
}
window.goHome = () => goPage('home');
window.deckBack = () => {
  if (!$('chooser').hidden) closeChooser();
  else if (!$('gameMenu').hidden) closeGameMenu();
  else if (page !== 'home') goPage('home');
  else if ($('homePages').scrollTop > 0) showHomePanel(0);
};

// ---- backdrop ----
let backdropFront = 'A', backdropSrc = '';
const ambientCache = new Map();

// A 64px copy of the artwork, upscaled by the browser, gives a soft ambient blur for free.
// The same small copy is what the theme samples, so `done` also receives the dominant colour.
function ambient(src, done) {
  if (ambientCache.has(src)) { const c = ambientCache.get(src); return done(c.url, c.accent); }
  const img = new Image();
  img.onload = () => {
    const canvas = document.createElement('canvas');
    canvas.width = 64; canvas.height = 36;
    const ctx = canvas.getContext('2d');
    const scale = Math.max(64 / img.width, 36 / img.height);
    const w = img.width * scale, h = img.height * scale;
    ctx.drawImage(img, (64 - w) / 2, (36 - h) / 2, w, h);
    const url = canvas.toDataURL('image/jpeg', .8);
    const accent = dominantAccent(ctx, 64, 36);
    ambientCache.set(src, { url, accent });
    done(url, accent);
  };
  img.onerror = () => done('', '');
  img.src = src;
}
function showBackdrop(src) {
  if (src === backdropSrc) return;
  backdropSrc = src;
  const front = $('backdrop' + backdropFront), back = $('backdrop' + (backdropFront === 'A' ? 'B' : 'A'));
  if (!src) { front.classList.remove('show'); back.classList.remove('show'); return; }
  back.src = src;
  const show = () => { back.classList.add('show'); front.classList.remove('show'); };
  back.complete && back.naturalWidth ? show() : (back.onload = show);
  backdropFront = backdropFront === 'A' ? 'B' : 'A';
}
function updateBackdrop() {
  const slug = videoTheme();
  const mode = slug ? 'video' : backgroundMode;
  document.body.dataset.background = mode;
  if (window.Deck && typeof Deck.showWallpaper === 'function') Deck.showWallpaper(mode === 'wallpaper');
  updateVideo(slug ? '/art/themes/' + slug + '.mp4' : '');
  syncTheme();
  if (mode !== 'art' && mode !== 'custom') return showBackdrop('');
  if (backgroundMode === 'custom') return showBackdrop(state.wallpaper ? artUrl(state.wallpaper) : '');
  const game = current();
  const src = game ? (game.cover ? thumbUrl(game.cover) : artUrl(game.background || game.icon)) : '';
  if (!src) return showBackdrop('');
  ambient(src, url => { if (backgroundMode === 'art' && selected === game.id) showBackdrop(url); });
}

// ---- video backdrop ----
// A video theme puts its clip behind the launcher in place of the chosen background.
const video = $('backdropVideo');
video.onplaying = () => video.classList.add('show');
function updateVideo(src) {
  if (!src) {
    video.classList.remove('show');
    if (video.dataset.src) { delete video.dataset.src; video.pause(); video.removeAttribute('src'); video.load(); }
    return;
  }
  if (video.dataset.src !== src) { video.classList.remove('show'); video.dataset.src = src; video.src = src; }
  video.play().catch(() => {});
}
document.addEventListener('visibilitychange', () => {
  if (!video.dataset.src) return;
  if (document.hidden) video.pause(); else video.play().catch(() => {});
});

// ---- game preview clips ----
// Home plays the selected game's clip (imported from ES-DE or chosen in its menu) after a short
// pause on it, so flicking through covers never starts a string of videos.
const preview = $('previewVideo');
let previewTimer = 0, previewsOn = prefs.get('previews', true);
preview.onplaying = () => preview.classList.add('show');
function stopPreview() {
  clearTimeout(previewTimer);
  preview.classList.remove('show');
  if (preview.dataset.src) { delete preview.dataset.src; preview.pause(); preview.removeAttribute('src'); preview.load(); }
}
function updatePreview() {
  const game = page === 'home' && homePanel() === 0 && $('gameMenu').hidden ? current() : null;
  const src = previewsOn && !document.hidden && game && /^[a-f0-9]{24}-video\.mp4(\?v=\d+)?$/.test(game.video || '') ? '/art/' + game.video : '';
  if (src && preview.dataset.src === src) return;
  stopPreview();
  if (src) previewTimer = setTimeout(() => { preview.dataset.src = src; preview.src = src; preview.play().catch(() => {}); }, 1200);
}
document.addEventListener('visibilitychange', updatePreview);

// ---- theme ----
// One accent, surfaces tinted toward its hue, neutral text. Deck is the fixed default; a video
// theme shows its clip and takes the accent from the frames as they play; "Match background"
// samples whatever the Background rows show: the selected cover, the custom image, or the phone wallpaper.
const DECK = { accent: '#a5e75b', onAccent: '#101a08', bg: '#121413', surface: '#1c1f1d', surface2: '#262a28' };
let videoThemes = [];
try { videoThemes = window.Theme && typeof Theme.themes === 'function' ? JSON.parse(Theme.themes()) : []; } catch (e) { videoThemes = []; }
let themeName = prefs.get('theme', 'deck');
let accentCache = prefs.get('themeAccents', {});   // last sampled accent per video theme, for an instant first paint
let autoAccent = '', wallpaperColors = null, themeNote = '', watchingWallpaper = false, videoTimer = 0;
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const videoTheme = () => themeName.startsWith('video:') && videoThemes.includes(themeName.slice(6)) ? themeName.slice(6) : '';
const SMALL_WORDS = new Set(['a', 'an', 'at', 'in', 'of', 'on', 'or', 'the', 'to', 'with', 'near']);
function themeTitle(slug) {
  return slug.split('-').filter(Boolean).map((w, i) => i && SMALL_WORDS.has(w) ? w : w[0].toUpperCase() + w.slice(1)).join(' ');
}

function hexToRgb(hex) { const n = parseInt(hex.slice(1), 16); return [n >> 16 & 255, n >> 8 & 255, n & 255]; }
function rgbToHsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2, d = max - min;
  if (!d) return [0, 0, l];
  const s = d / (1 - Math.abs(2 * l - 1));
  let h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [h * 60, s, l];
}
function hslToRgb(h, s, l) {
  const f = n => { const k = (n + h / 30) % 12, a = s * Math.min(l, 1 - l); return Math.round(255 * (l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1)))); };
  return [f(0), f(8), f(4)];
}
function hslHex(h, s, l) { return '#' + hslToRgb(h, s, l).map(v => v.toString(16).padStart(2, '0')).join(''); }
function luminance(hex) {
  const [r, g, b] = hexToRgb(hex).map(v => { v /= 255; return v <= .03928 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4; });
  return .2126 * r + .7152 * g + .0722 * b;
}

// Vivid mid-tones vote for their hue; the winning hue (with its neighbours) becomes the accent,
// pulled into a lightness band that reads on dark surfaces. Grey content yields a neutral accent.
function dominantAccent(ctx, w, h) {
  const d = measure(ctx, w, h);
  return d ? hslHex(d.hue, clamp(d.sat, .55, .85), clamp(d.lig, .6, .72)) : '#e6e8e6';
}
// The raw vote: winning hue as a unit vector plus its mean saturation/lightness and how much of
// the frame took part, or null for frames with (almost) no colour in them.
function measure(ctx, w, h) {
  const d = ctx.getImageData(0, 0, w, h).data;
  const weight = new Float32Array(24), sin = new Float32Array(24), cos = new Float32Array(24);
  const sat = new Float32Array(24), lig = new Float32Array(24);
  let total = 0;
  for (let i = 0; i < d.length; i += 4) {
    const [hue, s, l] = rgbToHsl(d[i], d[i + 1], d[i + 2]);
    if (s < .22 || l < .1 || l > .92) continue;
    const v = s * (1 - Math.abs(l - .5) * 1.4);
    if (v <= 0) continue;
    const b = Math.floor(hue / 15) % 24, rad = hue * Math.PI / 180;
    weight[b] += v; sin[b] += Math.sin(rad) * v; cos[b] += Math.cos(rad) * v; sat[b] += s * v; lig[b] += l * v;
    total += v;
  }
  if (total < w * h * .002) return null;
  let best = 0, bestScore = -1;
  for (let b = 0; b < 24; b++) {
    const score = weight[(b + 23) % 24] * .5 + weight[b] + weight[(b + 1) % 24] * .5;
    if (score > bestScore) { bestScore = score; best = b; }
  }
  const bins = [(best + 23) % 24, best, (best + 1) % 24];
  const sum = f => bins.reduce((acc, b) => acc + f[b], 0);
  const wsum = sum(weight), len = Math.hypot(sum(sin), sum(cos)) || 1;
  return { x: sum(cos) / len, y: sum(sin) / len, hue: (Math.atan2(sum(sin), sum(cos)) * 180 / Math.PI + 360) % 360, sat: sum(sat) / wsum, lig: sum(lig) / wsum, share: total / (w * h) };
}

function palette(accent) {
  if (!accent) return DECK;
  const [h, s] = rgbToHsl(...hexToRgb(accent));
  const tint = s < .12 ? 0 : 1;
  return {
    accent,
    onAccent: luminance(accent) > .3 ? hslHex(h, tint ? .6 : 0, .07) : '#ffffff',
    bg: hslHex(h, .04 + .1 * tint, .075),
    surface: hslHex(h, .04 + .08 * tint, .115),
    surface2: hslHex(h, .04 + .07 * tint, .16)
  };
}
function applyPalette(p) {
  const root = document.documentElement.style;
  root.setProperty('--accent', p.accent);
  root.setProperty('--on-accent', p.onAccent);
  root.setProperty('--bg', p.bg);
  root.setProperty('--bg-rgb', hexToRgb(p.bg).join(', '));
  root.setProperty('--surface', p.surface);
  root.setProperty('--surface-2', p.surface2);
}
// Ignores tiny drifts so a playing video doesn't make the accent shimmer.
function differs(a, b) {
  if (!a || !b) return a !== b;
  const [h1, s1, l1] = rgbToHsl(...hexToRgb(a)), [h2, s2, l2] = rgbToHsl(...hexToRgb(b));
  const dh = Math.abs(h1 - h2);
  return Math.min(dh, 360 - dh) > 10 || Math.abs(s1 - s2) > .1 || Math.abs(l1 - l2) > .06;
}
function applyAccent(accent, note) {
  themeNote = note;
  if (differs(accent, autoAccent)) {
    autoAccent = accent;
    applyPalette(palette(accent));
    const slug = videoTheme();
    if (slug && accent) { accentCache[slug] = accent; prefs.set('themeAccents', accentCache); }
    document.querySelectorAll('#themeRows .row[data-live][aria-pressed="true"] .swatch').forEach(el => { el.style.background = accent || 'transparent'; });
  }
  if (page === 'settings') $('themeHint').textContent = themeNote;
}
function wallpaperAccent() {
  if (!wallpaperColors) return '';
  const picks = ['primary', 'secondary', 'tertiary'].map(k => wallpaperColors[k]).filter(Boolean);
  let best = '', bestScore = -1;
  picks.forEach(hex => {
    const [, s, l] = rgbToHsl(...hexToRgb(hex));
    const score = s * (1 - Math.abs(l - .5) * 1.4);
    if (score > bestScore) { bestScore = score; best = hex; }
  });
  if (!best) return '';
  const [h, s, l] = rgbToHsl(...hexToRgb(best));
  return s < .22 ? '#e6e8e6' : hslHex(h, clamp(s, .55, .85), clamp(l, .6, .72));
}
window.receiveWallpaperColors = colors => {
  wallpaperColors = colors && colors.primary ? colors : null;
  if (themeName === 'auto' && backgroundMode === 'wallpaper') syncTheme();
};
window.receiveThemes = list => {
  videoThemes = list;
  if (page === 'settings') renderThemeRows();
  updateBackdrop();
};

// Video frames are averaged over the last ~10 s (hue as a vector, so red stays red across 0°),
// and frames with no colour in them — a fade to black, a dark scene — hold the current accent.
const videoCanvas = document.createElement('canvas');
videoCanvas.width = 48; videoCanvas.height = 27;
let videoAvg = null, videoAvgSrc = '';
function sampleVideo() {
  if (video.readyState < 2 || video.paused) return;
  if (video.dataset.src !== videoAvgSrc) { videoAvgSrc = video.dataset.src; videoAvg = null; }
  const ctx = videoCanvas.getContext('2d', { willReadFrequently: true });
  let m;
  try {
    ctx.drawImage(video, 0, 0, 48, 27);
    m = measure(ctx, 48, 27);
  } catch (error) {
    // Some WebViews refuse pixel access to an otherwise playable local video.
    // Keep the cached accent and playback; retry only after a theme change.
    clearInterval(videoTimer); videoTimer = 0;
    videoCanvas.width = 48;
    return;
  }
  if (!m) { if (!autoAccent) applyAccent('#e6e8e6', 'The colours follow the video as it plays.'); return; }
  const k = videoAvg ? .3 : 1;
  videoAvg = videoAvg ? { x: videoAvg.x + (m.x - videoAvg.x) * k, y: videoAvg.y + (m.y - videoAvg.y) * k, sat: videoAvg.sat + (m.sat - videoAvg.sat) * k, lig: videoAvg.lig + (m.lig - videoAvg.lig) * k } : m;
  const hue = (Math.atan2(videoAvg.y, videoAvg.x) * 180 / Math.PI + 360) % 360;
  applyAccent(hslHex(hue, clamp(videoAvg.sat, .55, .85), clamp(videoAvg.lig, .6, .72)), 'The colours follow the video as it plays.');
}

function syncTheme() {
  const slug = videoTheme();
  const watch = themeName === 'auto' && backgroundMode === 'wallpaper';
  if (watch !== watchingWallpaper && window.Theme && typeof Theme.watchWallpaper === 'function') { watchingWallpaper = watch; Theme.watchWallpaper(watch); }
  clearInterval(videoTimer); videoTimer = 0;
  if (slug) {
    applyAccent(accentCache[slug] || '', 'The colours follow the video as it plays.');
    videoTimer = setInterval(sampleVideo, 2000);
    return sampleVideo();
  }
  if (themeName !== 'auto') return applyAccent('', 'Nomad’s own green.');
  switch (backgroundMode) {
    case 'art': {
      const game = current();
      const src = game ? (game.cover ? thumbUrl(game.cover) : artUrl(game.background || game.icon)) : '';
      if (!src) return applyAccent('', 'No artwork to follow yet.');
      return ambient(src, (url, accent) => { if (themeName === 'auto' && backgroundMode === 'art' && selected === game.id) applyAccent(accent, 'Following the selected game’s artwork.'); });
    }
    case 'custom': {
      const src = state.wallpaper ? artUrl(state.wallpaper) : '';
      if (!src) return applyAccent('', 'Choose an image below to follow.');
      return ambient(src, (url, accent) => { if (themeName === 'auto' && backgroundMode === 'custom') applyAccent(accent, 'Following your image.'); });
    }
    case 'wallpaper': {
      const accent = wallpaperAccent();
      return applyAccent(accent, accent ? 'Following the phone wallpaper.' : 'This wallpaper doesn’t report its colours, so Deck green stays.');
    }
    default:
      return applyAccent('', 'Plain background, so Deck green stays. Choose a background below to follow it.');
  }
}
function setTheme(name) {
  themeName = name;
  prefs.set('theme', name);
  updateBackdrop();
  if (page === 'settings') renderSettings();
}
function themeRow(key, title, sub, swatch, live) {
  const row = document.createElement('button');
  row.className = 'row';
  row.dataset.theme = key;
  if (live) row.dataset.live = '';
  row.setAttribute('aria-pressed', String(key === themeName));
  const label = document.createElement('span');
  label.className = 'theme-label';
  const dot = document.createElement('i');
  dot.className = 'swatch';
  dot.style.background = live ? (key === themeName && autoAccent ? autoAccent : 'transparent') : swatch;
  const text = document.createElement('span');
  const name = document.createElement('span');
  name.textContent = title;
  text.append(name);
  if (sub) { const small = document.createElement('small'); small.textContent = sub; text.append(small); }
  label.append(dot, text);
  const radio = document.createElement('i');
  radio.className = 'radio';
  row.append(label, radio);
  row.onclick = () => setTheme(key);
  return row;
}
function renderThemeRows() {
  const rows = $('themeRows');
  rows.replaceChildren();
  rows.append(themeRow('deck', 'Deck', '', DECK.accent, false));
  rows.append(themeRow('auto', 'Match background', 'Follows the background below', '', true));
  videoThemes.forEach(slug => {
    const row = themeRow('video:' + slug, themeTitle(slug), 'Video', accentCache[slug] || '', true);
    if (accentCache[slug]) row.querySelector('.swatch').style.background = accentCache[slug];
    rows.append(row);
  });
  $('themeHint').textContent = themeNote;
}
$('addThemeButton').onclick = () => {
  if (window.Theme && typeof Theme.pickVideo === 'function') Theme.pickVideo(); else notify('Available in the Android app.');
};

// ---- covers ----
function coverEl(game) {
  const el = document.createElement('div');
  el.className = 'cover';
  const cover = thumbUrl(game.cover);
  if (cover) {
    const img = document.createElement('img');
    img.src = cover; img.alt = ''; img.loading = 'lazy'; img.decoding = 'async';
    el.append(img);
  } else if (game.android || artUrl(game.icon)) {
    const box = document.createElement('div');
    box.className = 'cover-app';
    const img = document.createElement('img');
    img.src = artUrl(game.icon); img.alt = '';
    const label = document.createElement('span');
    label.textContent = game.title;
    box.append(img, label);
    el.append(box);
  } else {
    const fallback = document.createElement('span');
    fallback.className = 'cover-fallback';
    fallback.textContent = game.title;
    el.append(fallback);
  }
  if (game.favorite) {
    const star = document.createElement('span');
    star.className = 'tile-star';
    star.innerHTML = icon('star', true);
    el.append(star);
  }
  return el;
}

// ---- home ----
function coversThatFit() {
  const row = $('coverRow');
  const styles = getComputedStyle(document.documentElement);
  const w = parseFloat(styles.getPropertyValue('--cover-w')) || 88;
  const gap = parseFloat(styles.getPropertyValue('--cover-gap')) || 14;
  const width = row.clientWidth || window.innerWidth - 48;
  // Capped at seven: smaller covers should give the backdrop room, not let more covers in.
  return Math.min(7, Math.max(3, Math.floor((width - 40) / (w + gap))));
}
function renderHome() {
  const row = $('coverRow');
  row.replaceChildren();
  const games = homeGames().sort(byPlayed);
  const game = current();
  selected = game ? game.id : '';
  const shown = games.slice(0, coversThatFit());
  const centre = Math.max(0, shown.findIndex(g => g.id === selected));
  shown.forEach((g, i) => {
    const el = coverEl(g);
    el.classList.toggle('selected', g.id === selected);
    // Distance from the selected cover: the boot reveal staggers outward from it.
    el.style.setProperty('--i', Math.abs(i - centre));
    el.setAttribute('role', 'button');
    el.setAttribute('aria-label', g.title);
    // First tap selects, second tap plays.
    el.onclick = () => { if (selected === g.id) playGame(g.id); else { selected = g.id; renderHome(); } };
    el.tabIndex = 0;
    el.addEventListener("contextmenu", e => e.preventDefault());
    row.append(el);
  });
  $('artButton').hidden = !game;
  renderHomeFavorites();
  if (!game) {
    if (!state.scanning && !games.length) {
      row.append(empty('Add your games', 'Choose the folders that hold your games. They stay where they are.', true));
    }
    $('heroTitle').textContent = state.scanning ? 'Finding your games…' : '';
    updateBackdrop();
    return;
  }
  $('heroTitle').textContent = game.title;
  updateBackdrop();
  updatePreview();
  sendSecondScreen(game);
}
// A second display (dual-screen handhelds) mirrors the selected game's art and stats.
let secondSignature = '';
function sendSecondScreen(game) {
  if (!window.Deck || typeof Deck.secondScreen !== 'function') return;
  const sys = game && !game.android ? systemOf(game) : null, a = game && game.achievements;
  const stats = !game ? [] : [
    game.playtimeMs ? `${formatDuration(game.playtimeMs)} played` : game.lastPlayed ? '' : 'Not played yet',
    game.lastPlayed ? `Last played ${new Date(game.lastPlayed).toLocaleDateString([], { month: 'short', day: 'numeric' })}` : '',
    a && a.total ? `${a.got} of ${a.total} achievements` : ''
  ].filter(Boolean);
  const payload = JSON.stringify(game ? { title: game.title, cover: game.cover, icon: game.icon, system: game.android ? 'Android' : sys ? sys.name : '', stats } : {});
  if (payload === secondSignature) return;
  secondSignature = payload;
  Deck.secondScreen(payload);
}

function empty(title, description, action = false) {
  const div = document.createElement('div');
  div.className = 'empty-state';
  const h = document.createElement('h3'); h.textContent = title;
  const p = document.createElement('p'); p.textContent = description;
  div.append(h, p);
  if (action) {
    const b = document.createElement('button');
    b.className = 'primary';
    b.textContent = 'Choose game folder';
    b.onclick = () => native('chooseFolder');
    div.append(b);
  }
  return div;
}

function gameTile(g) {
  const el = document.createElement('div');
  el.className = 'game-tile' + (selected === g.id ? ' selected' : '');
  el.dataset.id = g.id;
  el.addEventListener('contextmenu', e => e.preventDefault());
  const play = document.createElement('button');
  play.className = 'game-tile-play';
  play.setAttribute('aria-label', `Play ${g.title}`);
  const title = document.createElement('div');
  title.className = 'game-title';
  title.textContent = g.title;
  play.append(coverEl(g), title);
  play.onclick = () => playGame(g.id);
  // Keep library actions on a separate, deliberate tap target so scrolling stays inert.
  const actions = document.createElement('button');
  actions.className = 'game-tile-actions';
  actions.type = 'button';
  actions.setAttribute('aria-label', `More options for ${g.title}`);
  actions.textContent = '⋯';
  actions.onclick = e => {
    e.stopPropagation();
    const r = actions.getBoundingClientRect();
    openGameMenu(g, r.right, r.bottom);
  };
  el.append(play, actions);
  return el;
}

function renderHomeFavorites() {
  const grid = $('homeFavorites'), scroll = grid.scrollTop;
  const games = homeGames().filter(g => g.favorite).sort(byPlayed);
  grid.replaceChildren();
  games.forEach(g => grid.append(gameTile(g)));
  if (!games.length) grid.append(empty('No favorites yet', 'Open a game’s ⋯ menu and choose Add to favorites.'));
  grid.scrollTop = scroll;
  restorePadFocus(grid);
}
function showHomePanel(index) {
  closeGameMenu();
  const pager = $('homePages');
  pager.scrollTo({ top: index * pager.clientHeight, behavior: 'smooth' });
}
// A downward swipe at the top of the nested favorites grid returns to Home.
// WebView can otherwise consume the whole gesture in the inner scroller.
let favoritesSwipe = null;
$('homeFavorites').addEventListener('touchstart', e => {
  favoritesSwipe = e.touches.length === 1 && $('homeFavorites').scrollTop <= 1
    ? { x: e.touches[0].clientX, y: e.touches[0].clientY } : null;
}, { passive: true });
$('homeFavorites').addEventListener('touchend', e => {
  if (!favoritesSwipe) return;
  const touch = e.changedTouches[0];
  const dy = touch.clientY - favoritesSwipe.y, dx = touch.clientX - favoritesSwipe.x;
  favoritesSwipe = null;
  if (dy > 70 && dy > Math.abs(dx) * 1.5) showHomePanel(0);
}, { passive: true });
$('homeFavorites').addEventListener('touchcancel', () => { favoritesSwipe = null; }, { passive: true });
$('homePages').addEventListener('scroll', () => { closeGameMenu(); updatePreview(); }, { passive: true });

// ---- library ----
// Tiles are added 60 at a time as the grid scrolls (or the controller moves past the last row),
// so a library of thousands opens as fast as a small one. Off-screen tiles skip layout and paint.
const LIBRARY_CHUNK = 60;
let libraryItems = [], libraryShown = 0;
function appendLibrary(count) {
  const grid = $('libraryGrid'), end = Math.min(libraryItems.length, libraryShown + count);
  if (libraryShown >= end) return false;
  const batch = document.createDocumentFragment();
  for (let i = libraryShown; i < end; i++) batch.append(gameTile(libraryItems[i]));
  grid.append(batch);
  libraryShown = end;
  return true;
}
$('libraryGrid').addEventListener('scroll', () => {
  const g = $('libraryGrid');
  if (g.scrollTop + g.clientHeight > g.scrollHeight - 900) appendLibrary(LIBRARY_CHUNK);
}, { passive: true });
function renderLibrary() {
  if (page !== 'library') return;   // goPage('library') renders it when it is shown
  const grid = $('libraryGrid'), scroll = grid.scrollTop, before = libraryShown;
  grid.replaceChildren();
  const search = $('search').value.trim().toLowerCase();
  const all = allGames();
  const games = all
    .filter(g => (!favoriteOnly || g.favorite) && g.title.toLowerCase().includes(search))
    .sort((a, b) => a.title.localeCompare(b.title));
  $('gameCount').textContent = all.length ? `${all.length} game${all.length === 1 ? '' : 's'}` : '';
  libraryItems = games;
  libraryShown = 0;
  appendLibrary(Math.max(LIBRARY_CHUNK, before));
  if (!games.length) {
    grid.append(all.length
      ? empty(search ? 'No matches' : 'No favorites yet', search ? 'Try a different title.' : 'Open a game’s ⋯ menu and choose Add to favorites.')
      : empty('Add your games', 'Choose the folder that holds your games.', true));
  }
  grid.scrollTop = scroll;
  restorePadFocus(grid);
}

function renderApps() {
  const grid = $('appsGrid');
  grid.replaceChildren();
  state.apps.forEach(app => {
    const b = document.createElement('button');
    b.className = 'app-tile';
    const img = document.createElement('img');
    img.src = artUrl(app.icon); img.alt = '';
    const span = document.createElement('span');
    span.textContent = app.title;
    b.append(img, span);
    b.onclick = () => native('openApp', app.package);
    grid.append(b);
  });
  if (!state.apps.length) grid.append(empty('Loading apps…', 'Installed apps will appear here.'));
}

// ---- now playing ----
window.receiveMedia = next => {
  media = next || { enabled: false, active: false };
  const strip = $('nowPlaying'), launch = $('npLaunch');
  strip.hidden = !media.active;
  launch.hidden = media.active || !media.enabled;
  if (media.active) {
    $('npTitle').textContent = media.title || 'Unknown track';
    $('npArtist').textContent = media.artist || '';
    const art = artUrl(media.art);
    if (art && $('npArt').getAttribute('src') !== art) $('npArt').src = art;
    $('npArt').hidden = !art;
    $('npToggle').innerHTML = icon(media.playing ? 'pause' : 'play');
  }
  renderMusicSettings();
};
$('npPrev').innerHTML = icon('previous');
$('npNext').innerHTML = icon('next');
$('npToggle').innerHTML = icon('play');
$('npPrev').onclick = () => native('media', 'previous');
$('npNext').onclick = () => native('media', 'next');
$('npToggle').onclick = () => native('media', 'toggle');
$('npOpen').onclick = () => native('media', 'open');
$('npLaunch').innerHTML = icon('music') + 'Spotify';
$('npLaunch').onclick = () => native('media', 'open');

// ---- state from native ----
// Native sends status on every tick and the library only when it changed (next.library, with a
// hash). A flat snapshot that carries games itself (the browser tests) counts as a library too.
window.receiveState = next => {
  const library = next.library || (next.games ? next : null);
  state = { ...state, ...next, ...(library || {}) };
  delete state.library;
  $('powerButton').hidden = !state.rootFeatures;
  $('rootFeaturesToggle').setAttribute('aria-checked', String(!!state.rootFeatures));
  if (library) {
    const sig = next.libraryHash || JSON.stringify(library.games) + JSON.stringify(library.apps), appSig = JSON.stringify(library.apps);
    if (sig !== signature || !signature) {
      signature = sig;
      renderHome(); renderLibrary();
    }
    if (appSig !== appSignature) { appSignature = appSig; renderApps(); }
  }
  if (backgroundMode === 'custom') updateBackdrop();
  settleBoot();

  $('network').innerHTML = icon('wifi');
  $('network').setAttribute('aria-label', `${state.network || 'Offline'} — Open Wi-Fi settings`);
  $('battery').innerHTML = (state.charging ? icon('bolt') : icon('battery')) + `<span>${state.battery >= 0 ? state.battery + '%' : '—'}</span>`;
  $('battery').setAttribute('aria-label', `Battery ${state.battery} percent${state.charging ? ', charging' : ''}`);

  document.body.classList.toggle('loading', state.scanning);
  $('refreshButton').disabled = state.scanning;
  $('rescanSetting').disabled = state.scanning;
  if (page === 'settings') renderSettings();
  const message = state.scanning ? '' : state.scanMessage || '';
  if (message && message !== lastScanMessage && signature) notify(message);
  lastScanMessage = message;
};

// ---- game menu (play, favorite, artwork, delete) via explicit options buttons ----
let menuGame = null, deleteArmed = false, menuOpenedAt = 0;
function openGameMenu(game, x, y) {
  menuGame = game; deleteArmed = false;
  const menu = $('gameMenu');
  $('menuTitle').textContent = game.title;
  const a = game.achievements;
  $('menuPlaytime').textContent = `${formatDuration(game.playtimeMs)} played · Last session ${formatDuration(game.lastSessionMs)}`
    + (a && a.total ? ` · ${a.got}/${a.total} achievements${a.hardcore === a.got && a.got ? ' (hardcore)' : ''}` : '');
  $('menuFavorite').textContent = game.favorite ? 'Remove from favorites' : 'Add to favorites';
  $('menuArtReset').hidden = !game.customCover;
  $('menuPreview').hidden = !!game.android;
  // Only discs with a known 60 FPS patch get the row; the launcher applies it through PPSSPP's cheat file.
  $('menuFps').hidden = !game.fpsPatch || game.fpsPatch === 'none';
  $('menuFps').textContent = game.fpsPatch === 'on' ? '60 FPS patch: on' : '60 FPS patch: off';
  // PS2 discs with a widescreen patch in NetherSX2's community database; applied through root at launch.
  $('menuWide').hidden = !game.wsPatch || game.wsPatch === 'none' || !state.rootFeatures;
  $('menuWide').textContent = game.wsPatch === 'on' ? 'Widescreen patch: on' : 'Widescreen patch: off';
  const sys = game.android ? null : systemOf(game);
  $('menuEmulator').hidden = !sys || sys.options.filter(o => o.installed).length < 2 && !game.emuChoice;
  if (sys) $('menuEmulator').textContent = 'Play with: ' + (optionLabel(sys, game.emuChoice) || optionLabel(sys, sys.choice) || 'none installed');
  $('menuVersion').hidden = !(game.versions && game.versions.length > 1);
  if (!$('menuVersion').hidden) $('menuVersion').textContent = 'Version: ' + (game.version || game.format);
  $('menuHide').hidden = !!game.android;
  $('menuDelete').textContent = game.android ? 'Uninstall…' : 'Delete game…';
  $('menuDelete').classList.remove('confirm');
  menu.hidden = false;
  menuOpenedAt = Date.now();
  menu.style.left = Math.max(12, Math.min(x, window.innerWidth - menu.offsetWidth - 12)) + 'px';
  menu.style.top = Math.max(12, Math.min(y, window.innerHeight - menu.offsetHeight - 12)) + 'px';
}
function closeGameMenu() { $('gameMenu').hidden = true; menuGame = null; closeChooser(); }
// A small list of choices in the same popup style as the game menu.
function openChooser(title, items, x, y) {
  const menu = $('chooser');
  $('chooserTitle').textContent = title;
  menu.querySelectorAll('button').forEach(b => b.remove());
  items.forEach(item => {
    const b = document.createElement('button');
    b.setAttribute('role', 'menuitemradio');
    if (item.checked !== undefined) b.setAttribute('aria-checked', String(!!item.checked));
    const text = document.createElement('span'); text.textContent = item.label;
    if (item.sub) { const small = document.createElement('small'); small.textContent = item.sub; text.append(small); }
    b.append(text);
    if (item.checked !== undefined) { const r = document.createElement('i'); r.className = 'radio'; b.append(r); }
    b.onclick = e => { e.stopPropagation(); closeChooser(); item.run(); };
    menu.append(b);
  });
  menu.hidden = false;
  menuOpenedAt = Date.now();
  menu.style.left = Math.max(12, Math.min(x, window.innerWidth - menu.offsetWidth - 12)) + 'px';
  menu.style.top = Math.max(12, Math.min(y, window.innerHeight - menu.offsetHeight - 12)) + 'px';
  if (typeof focusFirst === 'function') focusFirst(menu);
}
function closeChooser() { $('chooser').hidden = true; }
$('menuPlay').onclick = () => { const g = menuGame; closeGameMenu(); if (g) playGame(g.id); };
$('menuFavorite').onclick = () => { const g = menuGame; closeGameMenu(); if (g) native('favorite', g.id); };
$('menuArt').onclick = () => { const g = menuGame; closeGameMenu(); if (g) native('pickCover', g.id); };
$('menuPreview').onclick = e => {
  e.stopPropagation();
  const g = menuGame, r = $('gameMenu').getBoundingClientRect();
  closeGameMenu();
  if (!g) return;
  const items = [{ label: g.video ? 'Choose another video…' : 'Choose video…', run: () => native('pickPreview', g.id) }];
  if (g.video) items.push({ label: 'Remove video', run: () => native('clearPreview', g.id) });
  openChooser(`${g.title} · preview`, items, r.left, r.top);
};
$('menuArtReset').onclick = () => { const g = menuGame; closeGameMenu(); if (g) native('clearCover', g.id); };
$('menuEmulator').onclick = e => {
  e.stopPropagation();
  const g = menuGame, r = $('gameMenu').getBoundingClientRect();
  closeGameMenu();
  if (g) openEmulatorChooser('game', systemOf(g), g.emuChoice, r.left, r.top, g);
};
$('menuVersion').onclick = e => {
  e.stopPropagation();
  const g = menuGame, r = $('gameMenu').getBoundingClientRect();
  closeGameMenu();
  if (g) openChooser(`${g.title} · version`, g.versions.map(v => ({ label: v.label, checked: v.id === g.id, run: () => { selected = v.id; native('setVersion', v.id); } })), r.left, r.top);
};
$('menuHide').onclick = () => { const g = menuGame; closeGameMenu(); if (g) { if (selected === g.id) selected = ''; native('hide', g.id, true); notify(`${g.title} hidden. Settings › Library brings it back.`); } };
$('menuWide').onclick = () => { const g = menuGame; closeGameMenu(); if (g) native('wsPatch', g.id, g.wsPatch !== 'on'); };
$('menuFps').onclick = () => { const g = menuGame; closeGameMenu(); if (g) native('fpsPatch', g.id, g.fpsPatch !== 'on'); };
// Deleting a file is irreversible, so the first tap only arms the button.
$('menuDelete').onclick = e => {
  e.stopPropagation();
  if (!deleteArmed) {
    deleteArmed = true;
    $('menuDelete').textContent = menuGame.android ? 'Tap again to uninstall' : 'Tap again to delete the file';
    $('menuDelete').classList.add('confirm');
    return;
  }
  const g = menuGame; closeGameMenu();
  if (g) { if (selected === g.id) selected = ''; native('deleteGame', g.id); }
};
$('artButton').onclick = e => {
  e.stopPropagation();
  const g = current();
  if (!g) return;
  if (!$('gameMenu').hidden) return closeGameMenu();
  const r = $('artButton').getBoundingClientRect();
  openGameMenu(g, r.left, r.top - 8 - 230);
};
document.addEventListener('click', e => {
  if (!$('gameMenu').hidden && !$('gameMenu').contains(e.target)) closeGameMenu();
  if (!$('chooser').hidden && !$('chooser').contains(e.target)) closeChooser();
});
// The release of the long-press must not count as a tap on the menu that just appeared.
document.addEventListener('click', e => { if ((!$('gameMenu').hidden || !$('chooser').hidden) && Date.now() - menuOpenedAt < 500) { e.stopPropagation(); e.preventDefault(); } }, true);

// ---- settings ----
let section = prefs.get('settingsSection', 'library');
function showSection(name) {
  if (!document.querySelector(`.settings-nav button[data-section="${name}"]`)) name = 'library';
  section = name;
  prefs.set('settingsSection', name);
  document.querySelectorAll('.settings-nav button').forEach(b => b.classList.toggle('active', b.dataset.section === name));
  document.querySelectorAll('.settings-pane section').forEach(s => s.classList.toggle('active', s.dataset.section === name));
  $('settings').querySelector('.settings-pane').scrollTop = 0;
  schedulePerformancePolling();
}
document.querySelectorAll('.settings-nav button').forEach(b => b.onclick = () => showSection(b.dataset.section));

// ---- playtime and battery ----
function formatDuration(ms) {
  if (!Number.isFinite(ms) || ms <= 0) return '0 min';
  if (ms < 60000) return '<1 min';
  const minutes = Math.floor(ms / 60000);
  return minutes < 60 ? `${minutes} min` : `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}
function renderTracking() {
  const games = state.games.concat((state.apps || []).filter(a => a.game && !isEmulator({ ...a, android: true })));
  games.sort((a, b) => (b.playtimeMs || 0) - (a.playtimeMs || 0) || a.title.localeCompare(b.title));
  $('totalPlaytime').textContent = formatDuration(games.reduce((sum, g) => sum + (g.playtimeMs || 0), 0));
  $('trackingState').textContent = state.usageAccess ? 'Enabled · Manage Android usage access' : 'Enable Android usage access to start tracking';
  $('playtimeRows').replaceChildren();
  for (const game of games) {
    const row = document.createElement('div'); row.className = 'row';
    const name = document.createElement('span'); name.textContent = game.title;
    const last = document.createElement('small'); last.textContent = `Last session: ${formatDuration(game.lastSessionMs)}`;
    name.append(last);
    const value = document.createElement('span'); value.className = 'value'; value.textContent = formatDuration(game.playtimeMs);
    row.append(name, value); $('playtimeRows').append(row);
  }
  if (!games.length) $('playtimeRows').textContent = 'Add games to see your playtime here.';
  const b = state.batteryStats || {};
  const flow = b.flow || (state.charging ? 'charging' : 'discharging');
  const charging = flow === 'charging', full = flow === 'full';
  const labels = { charging: 'Charging', discharging: 'Discharging', full: 'Full', idle: 'Not charging' };
  $('batteryCharge').textContent = state.battery >= 0 ? `${state.battery}% · ${labels[flow] || 'Unknown'}${state.charging && flow === 'discharging' ? ' · Plugged in' : ''}` : 'Unavailable';
  $('batteryPower').textContent = Number.isFinite(b.watts) ? `${Math.abs(b.watts).toFixed(1)} W${b.watts > 0 ? ' in' : b.watts < 0 ? ' out' : ''}` : 'Unavailable on this device';
  const change = b.change ?? -(b.lost || 0);
  $('batteryLost').textContent = state.battery < 0 ? '—' : `${change > 0 ? '+' : ''}${change} percentage points`;
  $('batteryWindow').textContent = `Over ${formatDuration(b.observedMs)} observed`;
  $('batteryRate').textContent = b.ready ? `${charging ? '+' : '−'}${b.rate.toFixed(1)}% / hour` : '—';
  $('batteryEstimateLabel').textContent = charging || full ? 'Estimated time to full' : 'Estimated time remaining';
  const hasEstimate = Number.isFinite(b.remainingMs) && b.remainingMs >= 0 && !full && (charging || flow === 'discharging');
  $('batteryRemaining').textContent = full ? 'Fully charged' : hasEstimate ? `About ${formatDuration(b.remainingMs)}` : '—';
  $('batteryHint').textContent = full ? 'Battery is full.' : hasEstimate ? `${b.estimateSource || 'Observed percentage change'}. Estimate changes with game load, temperature, and brightness.` : flow === 'idle' ? 'Power is connected but the battery is not charging.' : 'Learning the battery rate. Percentage estimates need at least 10 minutes and a 2 percentage point change.';

}
$('battery').onclick = () => { goPage('settings'); showSection('battery'); };

// ---- storage ----
function formatBytes(n) {
  if (!n) return '0 MB';
  if (n >= 1e9) return (n / 1e9).toFixed(n >= 1e10 ? 0 : 1) + ' GB';
  return Math.max(1, Math.round(n / 1e6)) + ' MB';
}
function renderStorage() {
  let info = {};
  try { info = window.Deck && typeof Deck.storage === 'function' ? JSON.parse(Deck.storage()) : {}; } catch (e) { info = {}; }
  const total = info.total || 0, free = info.free || 0, used = total - free;
  $('storageFree').textContent = total ? formatBytes(free) : '—';
  $('storageUsed').textContent = total ? `${formatBytes(used)} used of ${formatBytes(total)}` : 'Available in the Android app';
  $('storageFill').style.width = total ? Math.round(used / total * 100) + '%' : '0';
  const bySystem = new Map();
  state.games.forEach(g => { const key = g.system || 'psp'; bySystem.set(key, (bySystem.get(key) || 0) + (g.size || 0)); });
  const names = Object.fromEntries((state.systems || []).map(s => [s.id, s.name]));
  const rows = $('storageRows');
  rows.replaceChildren();
  const add = (label, bytes, sub) => {
    const row = document.createElement('div');
    row.className = 'row';
    const left = document.createElement('span');
    const name = document.createElement('span'); name.textContent = label; left.append(name);
    if (sub) { const small = document.createElement('small'); small.textContent = sub; left.append(small); }
    const value = document.createElement('span'); value.className = 'value'; value.textContent = formatBytes(bytes);
    row.append(left, value);
    rows.append(row);
  };
  [...bySystem.entries()].sort((a, b) => b[1] - a[1]).forEach(([key, bytes]) => {
    const count = state.games.filter(g => (g.system || 'psp') === key).length;
    add(names[key] || key, bytes, `${count} ${count === 1 ? 'game' : 'games'}`);
  });
  if (info.themes) add('Video themes', info.themes);
  if (info.art) add('Artwork', info.art);
}

// Settings rows are rebuilt on every status tick; a controller's focus is put back on the same
// control (by id, then by its own and its row's text, then by position) so it does not drop out.
function renderSettings() {
  const pane = document.querySelector('.settings-pane'), active = document.activeElement;
  const rowText = el => (el.closest('.row') || el).textContent;
  const held = padMode && pane.contains(active)
    ? { id: active.id, text: active.textContent, row: rowText(active), index: padTargets(pane).indexOf(active) } : null;
  drawSettings();
  if (held && !pane.contains(document.activeElement)) {
    const list = padTargets(pane);
    const back = (held.id && $(held.id)) || list.find(el => el.textContent === held.text && rowText(el) === held.row)
      || list[Math.min(held.index, list.length - 1)];
    if (back) back.focus({ preventScroll: true });
  }
}
function drawSettings() {
  renderTracking();
  const rows = $('folderRows');
  rows.replaceChildren();
  state.folders.forEach(f => {
    const row = document.createElement('div');
    row.className = 'row';
    const label = document.createElement('span');
    label.textContent = f.name;
    const remove = document.createElement('button');
    remove.className = 'remove';
    remove.innerHTML = icon('close');
    remove.setAttribute('aria-label', 'Remove ' + f.name);
    remove.onclick = () => native('removeFolder', f.uri);
    row.append(label, remove);
    rows.append(row);
  });
  if (!state.folders.length) {
    const p = document.createElement('p');
    p.className = 'hint';
    p.textContent = 'No folders yet. Add the folders that hold your games — a subfolder named after a system (N64, PS1, PS2, GC, 3DS, SNES…, or ES-DE’s folder names) sets the system; anything else counts as PSP.';
    rows.append(p);
  }
  renderArtworkRows();
  $('autoArtToggle').setAttribute('aria-checked', String(state.autoArt !== false));
  $('previewsToggle').setAttribute('aria-checked', String(previewsOn));
  renderEmulatorRows();
  const hidden = state.games.filter(g => g.hidden);
  $('hiddenGames').hidden = !hidden.length;
  $('hiddenCount').textContent = `${hidden.length} ${hidden.length === 1 ? 'game' : 'games'}`;
  $('androidGamesToggle').setAttribute('aria-checked', String(androidGames));
  const second = state.secondScreen || {};
  $('secondScreenToggle').setAttribute('aria-checked', String(second.enabled !== false));
  $('secondScreenState').textContent = second.present ? 'Showing on the second screen' : 'Shows on a second display when one is connected';
  document.querySelectorAll('#backgroundMode button').forEach(b => b.classList.toggle('active', b.dataset.value === backgroundMode));
  $('wallpaperState').textContent = state.wallpaper ? 'Custom image set' : 'No image chosen';
  renderThemeRows();
  renderStorage();
  const slug = videoTheme();
  $('backgroundNote').hidden = !slug;
  $('backgroundNote').textContent = slug ? `The ${themeTitle(slug)} theme is showing its video. Pick Deck or Match background above to use these.` : '';
  $('backgroundRows').classList.toggle('disabled', !!slug);
  $('homeSettingText').textContent = state.defaultHome ? 'Nomad is the default' : 'Set Nomad as default';
  $('versionText').textContent = state.version || '';
  renderMusicSettings();
  renderSaves();
  const ra = state.retroAchievements || {};
  $('raSignedOut').hidden = !!ra.connected;
  $('raSignedIn').hidden = !ra.connected;
  $('raAccount').textContent = ra.user || '';
  $('raStatus').textContent = ra.status || 'Tap to refresh';
}
function timeAgo(ms) {
  const m = Math.round((Date.now() - ms) / 60000);
  return m < 1 ? 'just now' : m < 60 ? `${m} min ago` : m < 1440 ? `${Math.round(m / 60)} h ago` : `${Math.round(m / 1440)} d ago`;
}
function renderSaves() {
  const saves = state.saves || { sets: [] };
  $('syncFolderName').textContent = saves.folder || 'Choose a folder';
  $('clearSyncFolder').hidden = !saves.folder;
  const rows = $('saveRows');
  rows.replaceChildren();
  saves.sets.forEach(set => {
    const row = document.createElement('div');
    row.className = 'row';
    const label = document.createElement('span');
    const name = document.createElement('span'); name.textContent = set.label;
    const where = document.createElement('small'); where.textContent = set.where;
    label.append(name, where);
    const remove = document.createElement('button');
    remove.className = 'remove';
    remove.innerHTML = icon('close');
    remove.setAttribute('aria-label', 'Stop syncing ' + set.label);
    remove.onclick = () => native('removeSaveSet', set.label);
    row.append(label, remove);
    rows.append(row);
  });
  if (!saves.sets.length) {
    const p = document.createElement('p'); p.className = 'hint';
    p.textContent = 'No save folders yet.';
    rows.append(p);
  }
  $('findSaves').hidden = !state.rootFeatures;
  $('saveAutoToggle').setAttribute('aria-checked', String(saves.auto !== false));
  $('syncNow').disabled = !!saves.syncing;
  $('syncStatus').textContent = saves.syncing ? 'Syncing…' : saves.last ? `${saves.status} · ${timeAgo(saves.last)}` : 'Not synced yet';
}
function renderArtworkRows() {
  const rows = $('artworkRows');
  rows.replaceChildren();
  allGames().sort((a, b) => a.title.localeCompare(b.title)).forEach(g => {
    const row = document.createElement('div');
    row.className = 'row art-row';
    const label = document.createElement('span');
    label.className = 'art-label';
    const thumb = document.createElement('img');
    thumb.className = 'thumb'; thumb.alt = '';
    thumb.src = g.cover ? thumbUrl(g.cover) : artUrl(g.icon);
    const name = document.createElement('b');
    name.textContent = g.title;
    label.append(thumb, name);
    const actions = document.createElement('span');
    actions.className = 'art-actions';
    const change = document.createElement('button');
    change.className = 'inline'; change.textContent = g.customCover ? 'Change' : 'Choose';
    change.onclick = () => native('pickCover', g.id);
    actions.append(change);
    if (g.customCover) {
      const reset = document.createElement('button');
      reset.className = 'inline'; reset.textContent = 'Reset';
      reset.onclick = () => native('clearCover', g.id);
      actions.append(reset);
    }
    row.append(label, actions);
    rows.append(row);
  });
  if (!allGames().length) {
    const p = document.createElement('p'); p.className = 'hint'; p.textContent = 'Add games first.'; rows.append(p);
  }
}
function renderMusicSettings() {
  const enabled = media.enabled || state.mediaAccess;
  $('musicState').textContent = enabled ? (media.active ? 'Connected · ' + appName(media.app) : 'Ready · nothing playing') : 'Needed to show what is playing';
  $('musicAccess').textContent = enabled ? 'Granted' : 'Grant';
  $('musicAccess').disabled = enabled;
}
function appName(pkg) {
  const app = state.apps.find(a => a.package === pkg);
  return app ? app.title : pkg === 'com.spotify.music' ? 'Spotify' : 'music app';
}
$('addFolderSetting').onclick = () => native('chooseFolder');
$('autoArtToggle').onclick = () => native('setAutoArt', state.autoArt === false);
$('findCovers').onclick = () => { native('findCovers'); notify('Looking for covers…'); };
$('importMedia').onclick = () => native('importMedia');
$('syncFolder').onclick = () => native('chooseSyncFolder');
$('clearSyncFolder').innerHTML = icon('close');
$('clearSyncFolder').onclick = () => native('clearSyncFolder');
$('addSaveFolder').onclick = () => native('addSaveFolder');
$('findSaves').onclick = () => native('findSaveFolders');
$('saveAutoToggle').onclick = () => native('setSaveAuto', !(state.saves && state.saves.auto !== false));
$('syncNow').onclick = () => native('syncSaves');
$('raConnect').onclick = () => {
  if (!$('raUser').value.trim() || !$('raKey').value.trim()) return notify('Enter your username and Web API key.');
  native('setRetroAchievements', $('raUser').value, $('raKey').value);
  $('raKey').value = '';
};
$('raRefresh').onclick = () => native('refreshAchievements');
$('raDisconnect').onclick = () => native('setRetroAchievements', '', '');
$('previewsToggle').onclick = () => { previewsOn = !previewsOn; prefs.set('previews', previewsOn); renderSettings(); updatePreview(); };
$('rescanSetting').onclick = () => native('refresh');
$('hiddenGames').onclick = e => {
  e.stopPropagation();
  const r = $('hiddenGames').getBoundingClientRect();
  openChooser('Tap a game to show it again', state.games.filter(g => g.hidden).map(g => ({ label: g.title, sub: g.version, run: () => native('hide', g.id, false) })), r.right - 300, r.bottom);
};
$('androidGamesToggle').onclick = () => { androidGames = !androidGames; prefs.set('androidGames', androidGames); renderSettings(); renderHome(); renderLibrary(); };
// One row per system that has games: the emulator that will play them, tap to choose another.
function optionLabel(sys, key) {
  const o = sys && sys.options.find(o => o.key === key);
  return o ? o.label : '';
}
function renderEmulatorRows() {
  const rows = $('emulatorRows');
  rows.replaceChildren();
  (state.systems || []).filter(s => s.games > 0).forEach(sys => {
    const row = document.createElement('button');
    row.className = 'row';
    const label = document.createElement('span');
    const name = document.createElement('span'); name.textContent = sys.name;
    const small = document.createElement('small');
    const missing = sys.options.find(o => !o.installed && o.site) || sys.options[0];
    small.textContent = `${sys.games} ${sys.games === 1 ? 'game' : 'games'} · ` + (sys.choice
      ? optionLabel(sys, sys.choice) + (sys.pinned ? '' : ' · automatic')
      : `Needs ${missing ? missing.label : 'an emulator'}`);
    label.append(name, small);
    const chev = document.createElement('i'); chev.className = 'chev';
    row.append(label, chev);
    row.onclick = e => { e.stopPropagation(); const r = row.getBoundingClientRect(); openEmulatorChooser('system', sys, sys.pinned, r.right - 280, r.bottom); };
    rows.append(row);
  });
  if (!rows.childElementCount) {
    const p = document.createElement('p');
    p.className = 'hint';
    p.textContent = 'Add a game folder and the emulators for its systems show up here.';
    rows.append(p);
  }
}
// scope "system" pins an emulator for every game of a system; "game" for one game only.
function openEmulatorChooser(scope, sys, pinned, x, y, game) {
  const target = scope === 'game' ? game.id : sys.id;
  const items = [];
  const auto = scope === 'game' ? sys.options.find(o => o.key === sys.choice) : sys.options.find(o => o.installed);
  items.push({ label: 'Automatic', sub: auto ? auto.label : 'Nothing installed', checked: !pinned, run: () => native('setEmulator', scope, target, '') });
  sys.options.filter(o => o.installed).forEach(o => items.push({ label: o.label, checked: pinned === o.key, run: () => native('setEmulator', scope, target, o.key) }));
  if (scope === 'system' && sys.choice) items.push({ label: 'Open ' + optionLabel(sys, sys.choice).replace(/ \(.*/, ''), run: () => native('openEmulator', sys.id) });
  const seen = new Set();
  sys.options.filter(o => !o.installed && o.site && !seen.has(o.emulator) && seen.add(o.emulator))
    .forEach(o => items.push({ label: 'Get ' + o.label.replace(/ \(.*/, '') + '…', run: () => native('emulatorSite', o.emulator) }));
  openChooser(scope === 'game' ? `${game.title} · play with` : sys.name, items, x, y);
}
document.querySelectorAll('#backgroundMode button').forEach(b => b.onclick = () => {
  backgroundMode = b.dataset.value;
  prefs.set('background', backgroundMode);
  if (backgroundMode === 'custom' && !state.wallpaper) native('pickWallpaper');
  renderSettings(); updateBackdrop();
});
$('wallpaperPick').onclick = () => { backgroundMode = 'custom'; prefs.set('background', 'custom'); native('pickWallpaper'); };
$('blackWallpaper').onclick = () => native('blackWallpaper');
$('musicAccess').onclick = () => native('media', 'access');
$('openSpotify').onclick = () => native('openApp', 'com.spotify.music');
document.querySelectorAll('[data-setting]').forEach(b => b.onclick = () => native('settings', b.dataset.setting));

// ---- header controls ----
document.querySelectorAll('nav button').forEach(b => b.onclick = () => goPage(b.dataset.page));
$('settingsButton').innerHTML = icon('settings');
$('settingsButton').onclick = () => goPage(page === 'settings' ? 'home' : 'settings');
$('powerButton').innerHTML = icon('power');
$('powerButton').onclick = () => {
  if (window.Performance && typeof window.Performance.shutdown === 'function') window.Performance.shutdown();
  else notify('Power control is unavailable in this build.');
};
$('secondScreenToggle').onclick = () => native('setSecondScreen', !(state.secondScreen && state.secondScreen.enabled !== false));
$('rootFeaturesToggle').onclick = () => native('setRootFeatures', !state.rootFeatures);
$('network').onclick = () => native('settings', 'wifi');
$('search').oninput = renderLibrary;
$('favoritesFilter').innerHTML = icon('star') + 'Favorites';
$('favoritesFilter').onclick = () => {
  favoriteOnly = !favoriteOnly;
  $('favoritesFilter').classList.toggle('active', favoriteOnly);
  $('favoritesFilter').setAttribute('aria-pressed', String(favoriteOnly));
  $('favoritesFilter').innerHTML = icon('star', favoriteOnly) + 'Favorites';
  renderLibrary();
};
$('addGamesButton').innerHTML = icon('folderPlus');
$('addGamesButton').onclick = () => native('chooseFolder');
$('refreshButton').innerHTML = icon('refresh');
$('refreshButton').onclick = () => native('refresh');

function clock() { $('clock').textContent = new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }); }
clock(); setInterval(clock, 1000);
window.addEventListener('resize', () => { if (page === 'home') renderHome(); });

// ---- performance (root CPU/GPU profiles) ----
const performanceModes = {
  saver: { title: 'Power saver', cpu: 'Up to 1.375 / 1.419 GHz', gpu: 'Up to 545 MHz', note: 'About 70% of peak clocks. Frequencies still scale down at idle.' },
  balanced: { title: 'Balanced', cpu: 'Original limits and governors', gpu: 'Original frequency bounds', note: 'Restores the controls saved before the first change this boot.' },
  performance: { title: 'Performance', cpu: '0.975–2.0 / 1.002–2.05 GHz', gpu: '595–806 MHz', note: 'Higher minimum clocks for responsiveness. No overclock.' },
  turbo: { title: 'Turbo', cpu: '2.0 / 2.05 GHz requested', gpu: '806 MHz requested', note: 'Maximum supported clocks. More heat and battery use; thermal protection stays on.' }
};
let selectedMode = 'balanced', activeMode = '', performanceBusy = false, performanceAvailable = false;
let performanceTimer, performanceTimeout, syncSelection = true, performanceRequest = 'status', queuedPerformance = '';

function renderPerformanceControls() {
  const changing = !!queuedPerformance || (performanceBusy && performanceRequest !== 'status');
  $('applyPerformance').disabled = changing || !performanceAvailable || selectedMode === activeMode;
  $('applyPerformance').textContent = changing ? 'Applying…' : selectedMode === activeMode ? 'Applied' : 'Apply';
  $('resetPerformance').disabled = changing || !performanceAvailable || activeMode === 'balanced';
  $('retryPerformance').hidden = performanceAvailable || performanceBusy;
  document.querySelectorAll('[data-mode]').forEach(button => {
    button.disabled = changing;
    button.setAttribute('aria-pressed', String(button.dataset.mode === selectedMode));
    let mark = button.querySelector('.active-mark');
    if (button.dataset.mode === activeMode) {
      if (!mark) { mark = document.createElement('span'); mark.className = 'active-mark'; mark.textContent = 'Active'; button.insertBefore(mark, button.lastElementChild); }
    } else if (mark) mark.remove();
  });
}
function previewPerformance(mode) {
  const profile = performanceModes[mode];
  if (!profile) return;
  selectedMode = mode;
  $('modeCpu').textContent = profile.cpu;
  $('modeGpu').textContent = profile.gpu;
  $('modeNote').textContent = profile.note;
  renderPerformanceControls();
}
function requestPerformance(mode = 'status') {
  if (performanceBusy) {
    if (mode !== 'status' && performanceRequest === 'status') { queuedPerformance = mode; renderPerformanceControls(); }
    return;
  }
  if (!window.Performance || typeof window.Performance.apply !== 'function') {
    performanceAvailable = false;
    $('performanceStatus').textContent = 'Root controls are unavailable in this build.';
    renderPerformanceControls(); return;
  }
  performanceBusy = true;
  performanceRequest = mode;
  if (mode !== 'status') $('performanceStatus').textContent = 'Applying ' + performanceModes[mode].title + '…';
  renderPerformanceControls();
  performanceTimeout = setTimeout(() => {
    performanceBusy = false; performanceAvailable = false; activeMode = ''; queuedPerformance = '';
    $('performanceStatus').textContent = 'Root request timed out. Check Nomad’s root permission, then retry.';
    renderPerformanceControls();
  }, 30000);
  try { mode === 'status' ? window.Performance.status() : window.Performance.apply(mode); }
  catch (error) { window.receivePerformance({ available: false, error: 'Could not reach root controls.' }); }
}
window.receivePerformance = result => {
  clearTimeout(performanceTimeout);
  performanceBusy = false;
  performanceAvailable = result.available === true;
  if (result.error || !performanceAvailable) {
    queuedPerformance = '';
    activeMode = '';
    $('performanceStatus').textContent = result.error || 'Root controls unavailable.';
    $('liveCpu').textContent = $('liveGpu').textContent = $('liveTemperature').textContent = '—';
    renderPerformanceControls(); return;
  }
  activeMode = result.active;
  if (syncSelection || result.request !== 'status') {
    if (performanceModes[activeMode]) previewPerformance(activeMode);
    syncSelection = false;
  }
  const mhz = value => Number.isFinite(Number(value)) && Number(value) > 0 ? Math.round(Number(value) / 1000) : '—';
  $('liveCpu').textContent = mhz(result.cpu0) + ' / ' + mhz(result.cpu1) + ' MHz';
  $('liveGpu').textContent = mhz(result.gpu) + ' MHz';
  $('liveTemperature').textContent = Number.isFinite(Number(result.temperature)) ? (Number(result.temperature) / 10).toFixed(1) + ' °C' : '—';
  const name = performanceModes[activeMode]?.title;
  $('performanceStatus').textContent = name
    ? name + ' active · caps ' + mhz(result.cpu0max) + ' / ' + mhz(result.cpu1max) + ' MHz CPU, ' + mhz(result.gpuMax) + ' MHz GPU'
    : 'Controls were changed outside Nomad. Select a mode to apply it again.';
  if (result.request && result.request !== 'status' && !(page === 'settings' && section === 'performance')) notify((name || 'Mode') + ' active');
  renderPerformanceControls();
  if (queuedPerformance) { const mode = queuedPerformance; queuedPerformance = ''; requestPerformance(mode); }
};
// Live readings refresh only while the Performance section is on screen.
function schedulePerformancePolling() {
  clearInterval(performanceTimer);
  if (page === 'settings' && section === 'performance') {
    syncSelection = true;
    requestPerformance();
    performanceTimer = setInterval(() => { if (!document.hidden && performanceAvailable) requestPerformance(); }, 5000);
  }
}
$('resetPerformance').onclick = () => { previewPerformance('balanced'); requestPerformance('balanced'); };
$('applyPerformance').onclick = () => requestPerformance(selectedMode);
$('retryPerformance').onclick = () => requestPerformance();
document.querySelectorAll('[data-mode]').forEach(button => button.onclick = () => previewPerformance(button.dataset.mode));
previewPerformance('balanced');

// ---- controller ----
// Native forwards gamepad buttons, the D-pad and the sticks as window.deckInput(name). Focus moves
// spatially between whatever is on screen; Home's cover row moves the selection instead of focus.
// A plays or presses, B goes back, X/Start opens a game's menu, Y favorites, L1/R1 switch pages
// (settings sections inside Settings), Select opens Settings. Touch hides the focus ring again.
let padMode = false, padReturn = null, padFocusId = '';
const PAGES = ['home', 'library', 'apps'];
function setPad(on) {
  if (padMode === on) return;
  padMode = on;
  document.body.classList.toggle('pad', on);
}
document.addEventListener('pointerdown', () => setPad(false), true);

function isVisible(el) {
  if (!el || el.disabled || el.closest('[hidden]')) return false;
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < window.innerHeight + 400;
}
function padTargets(scope) {
  return [...scope.querySelectorAll('button, input, [tabindex="0"]')]
    .filter(el => !el.classList.contains('game-tile-actions') && !(el.closest('#coverRow')) && isVisible(el));
}
function focusEl(el) {
  if (!el) return;
  el.focus({ preventScroll: true });
  // Scroll only the list holding the target; scrollIntoView would also shift the fixed-height shell.
  const box = el.closest('.library-grid, .apps-grid, .settings-pane, .settings-nav, .menu');
  if (box) {
    const r = el.getBoundingClientRect(), b = box.getBoundingClientRect();
    if (r.top < b.top + 8) box.scrollTop -= b.top + 8 - r.top;
    else if (r.bottom > b.bottom - 8) box.scrollTop += r.bottom - b.bottom + 8;
  }
  const tile = el.closest('[data-id]');
  padFocusId = tile ? tile.dataset.id : '';
  // The second screen follows the focused game in Library and Favorites, not only Home's selection.
  if (tile) { const game = allGames().find(g => g.id === tile.dataset.id); if (game) sendSecondScreen(game); }
  if (el.closest('.settings-nav') && el.dataset.section !== section) showSection(el.dataset.section);
}
function focusFirst(scope) { if (padMode) focusEl(padTargets(scope)[0]); }
// A re-render replaces the grid; put the focus back on the same game.
function restorePadFocus(grid) {
  if (!padMode || !padFocusId || !grid.closest('.page.active')) return;
  const tile = grid.querySelector(`[data-id="${CSS.escape(padFocusId)}"] .game-tile-play`);
  if (tile && document.activeElement !== tile) tile.focus({ preventScroll: true });
}
const openMenuEl = () => !$('chooser').hidden ? $('chooser') : !$('gameMenu').hidden ? $('gameMenu') : null;

// The nearest target in a direction. Targets sharing the current row (or column) win over
// closer ones that are not in line, so "right" from the settings list goes into its rows.
function nearest(from, dir, list) {
  const a = from.getBoundingClientRect(), ax = a.left + a.width / 2, ay = a.top + a.height / 2;
  const horizontal = dir === 'left' || dir === 'right';
  let best = null, bestScore = Infinity;
  for (const el of list) {
    if (el === from || el.contains(from) || from.contains(el)) continue;
    const b = el.getBoundingClientRect(), bx = b.left + b.width / 2, by = b.top + b.height / 2;
    const dx = bx - ax, dy = by - ay;
    let along, across;
    if (dir === 'up') { if (b.bottom > a.top + 4 && dy > -8) continue; along = -dy; across = Math.abs(dx); }
    else if (dir === 'down') { if (b.top < a.bottom - 4 && dy < 8) continue; along = dy; across = Math.abs(dx); }
    else if (dir === 'left') { if (b.right > a.left + 4 && dx > -8) continue; along = -dx; across = Math.abs(dy); }
    else { if (b.left < a.right - 4 && dx < 8) continue; along = dx; across = Math.abs(dy); }
    const overlaps = horizontal ? b.top < a.bottom && b.bottom > a.top : b.left < a.right && b.right > a.left;
    const score = (overlaps ? 0 : 100000) + along + across * 2.5;
    if (score < bestScore) { bestScore = score; best = el; }
  }
  return best;
}
function entryPoint() {
  const last = padFocusId && $(page) && $(page).querySelector(`[data-id="${CSS.escape(padFocusId)}"] .game-tile-play`);
  if (last && isVisible(last)) return last;
  if (page === 'library') return $('libraryGrid').querySelector('.game-tile-play') || padTargets($('library'))[0];
  if (page === 'apps') return $('appsGrid').querySelector('button');
  if (page === 'settings') return document.querySelector('.settings-nav button.active');
  return $('homeFavorites').querySelector('.game-tile-play');
}
function homePanel() { const p = $('homePages'); return Math.round(p.scrollTop / Math.max(1, p.clientHeight)); }

function menuInput(menu, btn) {
  const items = padTargets(menu);
  let i = items.indexOf(document.activeElement);
  if (btn === 'up' || btn === 'down') focusEl(items[i < 0 ? 0 : (i + (btn === 'down' ? 1 : -1) + items.length) % items.length]);
  else if (btn === 'a') { menuOpenedAt = 0; (items[i] || items[0]).click(); }
  else if (btn === 'b') window.deckBack();
  if (!openMenuEl()) { if (isVisible(padReturn)) focusEl(padReturn); else padReturn = null; }
  else if (!menu.contains(document.activeElement)) focusFirst(openMenuEl());
}
function openMenuFor(game, anchor) {
  if (!game) return;
  padReturn = document.activeElement;
  const r = anchor.getBoundingClientRect();
  openGameMenu(game, r.left, r.bottom);
  menuOpenedAt = 0;
  focusFirst($('gameMenu'));
}
function homeInput(btn) {
  const header = document.querySelector('header');
  if (header.contains(document.activeElement)) {
    if (btn !== 'down') return false;
    document.activeElement.blur();
    return true;
  }
  if (homePanel() === 1) {
    if (btn === 'up' && !(document.activeElement && $('homeFavorites').contains(document.activeElement) && nearest(document.activeElement, 'up', padTargets($('homeFavorites'))))) {
      showHomePanel(0); document.activeElement && document.activeElement.blur(); return true;
    }
    return false;
  }
  const games = homeGames().sort(byPlayed).slice(0, $('coverRow').querySelectorAll('.cover').length);
  const i = games.findIndex(g => g.id === selected), game = current();
  switch (btn) {
    case 'left': case 'right': {
      const next = games[i + (btn === 'right' ? 1 : -1)];
      if (next) { selected = next.id; renderHome(); }
      return true;
    }
    case 'a': if (game) playGame(game.id); return true;
    case 'x': case 'start': openMenuFor(game, $('artButton')); return true;
    case 'y': if (game) native('favorite', game.id); return true;
    case 'down': showHomePanel(1); setTimeout(() => focusFirst($('homeFavorites')), 400); return true;
    case 'up': focusEl(document.querySelector('nav button.active')); return true;
  }
  return false;
}
window.deckInput = btn => {
  // Focus left behind by touch is not a controller position; start fresh.
  if (!padMode && !openMenuEl() && document.activeElement && document.activeElement !== $('search')) document.activeElement.blur();
  setPad(true);
  const menu = openMenuEl();
  if (menu) return menuInput(menu, btn);
  if (btn === 'l1' || btn === 'r1') {
    const step = btn === 'r1' ? 1 : -1;
    if (page === 'settings') {
      const names = [...document.querySelectorAll('.settings-nav button')].map(b => b.dataset.section);
      showSection(names[(names.indexOf(section) + step + names.length) % names.length]);
      focusEl(document.querySelector('.settings-nav button.active'));
    } else {
      goPage(PAGES[(Math.max(0, PAGES.indexOf(page)) + step + PAGES.length) % PAGES.length]);
      if (page !== 'home') focusEl(entryPoint());
    }
    return;
  }
  if (btn === 'select') { goPage(page === 'settings' ? 'home' : 'settings'); if (page === 'settings') focusEl(entryPoint()); return; }
  if (btn === 'b') {
    if (document.activeElement === $('search')) { $('search').blur(); focusEl(entryPoint()); return; }
    window.deckBack();
    if (page !== 'home' && !document.querySelector('.page.active').contains(document.activeElement)) focusEl(entryPoint());
    return;
  }
  if (page === 'home' && homeInput(btn)) return;
  const el = document.activeElement;
  const scopes = [document.querySelector('header'), $(page)];
  const inScope = el && el !== document.body && scopes.some(s => s.contains(el)) && isVisible(el);
  if (['up', 'down', 'left', 'right'].includes(btn)) {
    if (!inScope) return focusEl(entryPoint());
    // The header and the page are separate zones: sideways moves stay in their zone, and up/down
    // cross over only when nothing is left in that direction.
    const zone = scopes.find(s => s.contains(el)), other = scopes.find(s => s !== zone);
    let next = nearest(el, btn, padTargets(zone));
    if (!next && page === 'library' && (btn === 'down' || btn === 'right') && appendLibrary(LIBRARY_CHUNK)) next = nearest(el, btn, padTargets(zone));
    if (!next && (btn === 'up' || btn === 'down')) next = btn === 'down' && zone === scopes[0] ? entryPoint() : nearest(el, btn, padTargets(other));
    if (next) focusEl(next);
    return;
  }
  if (!inScope) return focusEl(entryPoint());
  const tile = el.closest('.game-tile');
  const game = tile && allGames().find(g => g.id === tile.dataset.id);
  if (btn === 'a') { el.click(); return; }
  if ((btn === 'x' || btn === 'start') && game) openMenuFor(game, tile);
  if (btn === 'y' && game) native('favorite', game.id);
};
// A keyboard drives the same inputs; typing in the search field stays untouched.
document.addEventListener('keydown', e => {
  const typing = e.target.tagName === 'INPUT';
  const map = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'a', Escape: 'b' };
  const btn = map[e.key];
  if (!btn || typing && !['up', 'down', 'b'].includes(btn)) return;
  e.preventDefault(); e.stopPropagation();
  window.deckInput(btn);
}, true);

// ---- boot ----
// Reveal only a populated native snapshot and decoded first-screen assets, once per page.
let booted = false, bootPreparing = false;
// Artwork or native loading must never keep the entire launcher invisible indefinitely.
const bootDeadline = setTimeout(finishBoot, 6000);
function finishBoot() {
  if (booted) return;
  clearTimeout(bootDeadline);
  booted = true;
  bootPreparing = false;
  document.body.classList.replace('booting', 'intro');
  setTimeout(() => document.body.classList.remove('intro'), 1200);
}
function settleBoot() {
  if (booted || bootPreparing || !state.appsLoaded || state.scanning) return;
  reveal();
}
async function reveal() {
  if (booted || bootPreparing) return;
  bootPreparing = true;
  const snapshot = signature;
  const images = [...document.querySelectorAll('#coverRow img, .backdrop img, .now-playing img')]
    .filter(img => img.getAttribute('src'));
  await Promise.all(images.map(img => img.decode().catch(() => {})));
  if (document.fonts) await document.fonts.ready;
  if (video.dataset.src && video.readyState < 2) {
    await new Promise(done => {
      const finish = () => {
        clearTimeout(timeout);
        video.removeEventListener('loadeddata', finish);
        video.removeEventListener('error', finish);
        done();
      };
      const timeout = setTimeout(finish, 5000);
      video.addEventListener('loadeddata', finish, { once: true });
      video.addEventListener('error', finish, { once: true });
    });
  }
  requestAnimationFrame(() => requestAnimationFrame(() => {
    if (booted) return;
    bootPreparing = false;
    if (snapshot !== signature || state.scanning) { settleBoot(); return; }
    finishBoot();
  }));
}

showSection(section);
document.body.dataset.page = page;
renderHome();
native('ready');
