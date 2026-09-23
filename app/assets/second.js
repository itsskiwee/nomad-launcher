'use strict';
// The second screen shows whatever is selected on the main one (see SecondScreen.java).
const $ = id => document.getElementById(id);
const safeArt = name => name && /^[a-zA-Z0-9_-]+\.(jpg|png)(\?v=\d+)?$/.test(name) ? '/art/' + name : '';

window.showGame = game => {
  const has = game && game.title;
  $('game').hidden = !has;
  $('idle').hidden = !!has;
  if (!has) { $('ambient').classList.remove('show'); return; }
  const art = safeArt(game.cover) || safeArt(game.icon);
  $('cover').hidden = !art;
  if (art && $('cover').getAttribute('src') !== art) $('cover').src = art;
  if (art && $('ambient').getAttribute('src') !== art) $('ambient').src = art;
  $('ambient').classList.toggle('show', !!art);
  $('system').textContent = game.system || '';
  $('title').textContent = game.title;
  $('stats').replaceChildren(...(game.stats || []).map(line => { const d = document.createElement('div'); d.textContent = line; return d; }));
};
function clock() { $('idle').textContent = new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }); }
clock();
setInterval(clock, 10000);
