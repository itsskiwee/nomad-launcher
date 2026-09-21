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

// PSP games and installed Android games share one library shape.
function allGames() {
  const psp = state.games.map(g => ({ ...g, android: false }));
  const apps = androidGames ? state.apps
    .map(a => ({ ...a, android: true }))
    .filter(a => a.game && !isEmulator(a)) : [];
  return psp.concat(apps);
}
// Emulators never appear in the game library; they stay reachable in Apps and Settings.
const isEmulator = g => g.android && /^(org\.ppsspp\.ppsspp(gold)?|com\.retroarch(\.aarch64)?|com\.github\.stenzek\.duckstation|org\.mupen64plusae\.v3\.fzurita|xyz\.aethersx2\.android)$/.test(g.package);
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
}
window.goHome = () => goPage('home');
window.deckBack = () => {
  if (!$('gameMenu').hidden) closeGameMenu();
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
  const src = game ? artUrl(game.cover || game.background || game.icon) : '';
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
      const src = game ? artUrl(game.cover || game.background || game.icon) : '';
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
  const cover = artUrl(game.cover);
  if (cover) {
    const img = document.createElement('img');
    img.src = cover; img.alt = ''; img.loading = 'lazy';
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
    el.onkeydown = e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); el.click(); } };
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
$('homePages').addEventListener('scroll', closeGameMenu, { passive: true });
$('homePages').addEventListener('keydown', e => {
  if (e.target !== $('homePages')) return;
  if (e.key === 'ArrowDown' || e.key === 'PageDown') { e.preventDefault(); showHomePanel(1); }
  if (e.key === 'ArrowUp' || e.key === 'PageUp') { e.preventDefault(); showHomePanel(0); }
});

// ---- library ----
function renderLibrary() {
  const grid = $('libraryGrid'), scroll = grid.scrollTop;
  grid.replaceChildren();
  const search = $('search').value.trim().toLowerCase();
  const all = allGames();
  const games = all
    .filter(g => (!favoriteOnly || g.favorite) && g.title.toLowerCase().includes(search))
    .sort((a, b) => a.title.localeCompare(b.title));
  $('gameCount').textContent = all.length ? `${all.length} game${all.length === 1 ? '' : 's'}` : '';
  games.forEach(g => grid.append(gameTile(g)));
  if (!games.length) {
    grid.append(all.length
      ? empty(search ? 'No matches' : 'No favorites yet', search ? 'Try a different title.' : 'Open a game’s ⋯ menu and choose Add to favorites.')
      : empty('Add your games', 'Choose the folder that holds your games.', true));
  }
  grid.scrollTop = scroll;
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
window.receiveState = next => {
  state = next;
  $('powerButton').hidden = !state.rootFeatures;
  $('rootFeaturesToggle').setAttribute('aria-checked', String(!!state.rootFeatures));
  const sig = JSON.stringify(state.games) + JSON.stringify(state.apps), appSig = JSON.stringify(state.apps);
  if (sig !== signature || !signature) {
    signature = sig;
    renderHome(); renderLibrary();
  }
  if (appSig !== appSignature) { appSignature = appSig; renderApps(); }
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
  $('menuFavorite').textContent = game.favorite ? 'Remove from favorites' : 'Add to favorites';
  $('menuArtReset').hidden = !game.cover;
  $('menuDelete').textContent = game.android ? 'Uninstall…' : 'Delete game…';
  $('menuDelete').classList.remove('confirm');
  menu.hidden = false;
  menuOpenedAt = Date.now();
  menu.style.left = Math.max(12, Math.min(x, window.innerWidth - menu.offsetWidth - 12)) + 'px';
  menu.style.top = Math.max(12, Math.min(y, window.innerHeight - menu.offsetHeight - 12)) + 'px';
}
function closeGameMenu() { $('gameMenu').hidden = true; menuGame = null; }
$('menuPlay').onclick = () => { const g = menuGame; closeGameMenu(); if (g) playGame(g.id); };
$('menuFavorite').onclick = () => { const g = menuGame; closeGameMenu(); if (g) native('favorite', g.id); };
$('menuArt').onclick = () => { const g = menuGame; closeGameMenu(); if (g) native('pickCover', g.id); };
$('menuArtReset').onclick = () => { const g = menuGame; closeGameMenu(); if (g) native('clearCover', g.id); };
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
document.addEventListener('click', e => { if (!$('gameMenu').hidden && !$('gameMenu').contains(e.target)) closeGameMenu(); });
// The release of the long-press must not count as a tap on the menu that just appeared.
document.addEventListener('click', e => { if (!$('gameMenu').hidden && Date.now() - menuOpenedAt < 500) { e.stopPropagation(); e.preventDefault(); } }, true);

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
  const names = { psp: 'PSP', ps1: 'PlayStation', ps2: 'PlayStation 2', n64: 'Nintendo 64', snes: 'Super Nintendo', nes: 'NES', gba: 'Game Boy Advance', gb: 'Game Boy', genesis: 'Genesis', '32x': '32X', nds: 'Nintendo DS', dreamcast: 'Dreamcast', arcade: 'Arcade', pce: 'PC Engine' };
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

function renderSettings() {
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
    p.textContent = 'No folders yet. Add the folders that hold your games — a subfolder named N64, PS1, PS2, SNES… sets the system; anything else counts as PSP.';
    rows.append(p);
  }
  renderArtworkRows();
  renderEmulatorRows();
  $('androidGamesToggle').setAttribute('aria-checked', String(androidGames));
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
    thumb.src = artUrl(g.cover || g.icon);
    const name = document.createElement('b');
    name.textContent = g.title;
    label.append(thumb, name);
    const actions = document.createElement('span');
    actions.className = 'art-actions';
    const change = document.createElement('button');
    change.className = 'inline'; change.textContent = g.cover ? 'Change' : 'Choose';
    change.onclick = () => native('pickCover', g.id);
    actions.append(change);
    if (g.cover) {
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
$('rescanSetting').onclick = () => native('refresh');
$('androidGamesToggle').onclick = () => { androidGames = !androidGames; prefs.set('androidGames', androidGames); renderSettings(); renderHome(); renderLibrary(); };
const EMULATOR_APPS = [
  { pkg: 'org.ppsspp.ppsspp', name: 'PPSSPP' },
  { pkg: 'org.ppsspp.ppssppgold', name: 'PPSSPP (Gold)' },
  { pkg: 'com.retroarch.aarch64', name: 'RetroArch' },
  { pkg: 'com.retroarch', name: 'RetroArch' },
  { pkg: 'com.github.stenzek.duckstation', name: 'DuckStation' },
  { pkg: 'org.mupen64plusae.v3.fzurita', name: 'N64 (M64Plus FZ)' },
  { pkg: 'org.mupen64plusae.v3.fzurita.pro', name: 'N64 (M64Plus FZ Pro)' },
];
function renderEmulatorRows() {
  const rows = $('emulatorRows'), seen = new Set();
  rows.replaceChildren();
  EMULATOR_APPS.forEach(e => {
    if (!state.apps.find(a => a.package === e.pkg) || seen.has(e.name)) return;
    seen.add(e.name);
    const row = document.createElement('button');
    row.className = 'row';
    const span = document.createElement('span');
    span.textContent = 'Open ' + e.name;
    const chev = document.createElement('i');
    chev.className = 'chev';
    row.append(span, chev);
    row.onclick = () => native('openApp', e.pkg);
    rows.append(row);
  });
  if (!rows.childElementCount) {
    const p = document.createElement('p');
    p.className = 'hint';
    p.textContent = 'No emulators installed.';
    rows.append(p);
  }
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
