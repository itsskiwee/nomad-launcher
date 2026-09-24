const { test, expect } = require('@playwright/test');
const fs = require('node:fs');
const titles = ['Orbit Run', 'Drift Circuit', 'Afterlight', 'Terra Nova', 'Northstar', 'Signal Lost', 'Moonrise', 'Outlands'];
const colors = ['#74c7b8', '#e6ac73', '#a39ccc', '#c3ce8a', '#90b5da', '#d39191', '#b8ab90', '#7da8a1'];
const snapshot = {
  games: titles.map((title, i) => ({ id: String(i), title, cover: `demo-${i}.jpg`, favorite: i < 2, plays: 8 - i })),
  apps: [], folders: [], appsLoaded: true, scanning: false, rootFeatures: false,
  battery: 85, network: 'Wi-Fi', version: '0.3.1.1',
};
async function openApp(page, state = snapshot) {
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/art/**', route => {
    const match = route.request().url().match(/demo-(\d+)/), i = match ? Number(match[1]) : 0;
    route.fulfill({ contentType: 'image/svg+xml', body: `<svg xmlns="http://www.w3.org/2000/svg" width="232" height="400"><rect width="232" height="400" fill="#1b2329"/><circle cx="170" cy="110" r="140" fill="${colors[i % colors.length]}"/><path d="M-40 340L250 60V160L-40 440Z" fill="#ffffff" opacity=".16"/><circle cx="155" cy="110" r="65" fill="#1b2329"/><text x="20" y="320" fill="#f0f0e8" font-family="sans-serif" font-size="21" font-weight="bold">${titles[i % titles.length].toUpperCase()}</text><text x="20" y="350" fill="#f0f0e8" opacity=".55" font-family="sans-serif" font-size="12">NOMAD · DEMO ART</text></svg>` });
  });
  await page.addInitScript(state => {
    window.calls = [];
    window.fixture = state;
    window.Deck = {
      ready: () => window.receiveState(state), acknowledge: () => {},
      play: id => window.calls.push(['play', id]),
      favorite: id => { state.games.find(g => g.id === id).favorite = !state.games.find(g => g.id === id).favorite; window.receiveState(state); },
      setRootFeatures: enabled => { state.rootFeatures = enabled; window.receiveState(state); },
      storage: () => '{}',
    };
    window.Performance = { status: () => window.calls.push(['status']), apply: mode => window.calls.push(['apply', mode]) };
  }, state);
  await page.goto('/');
  await expect(page.locator('body')).not.toHaveClass(/booting/);
  await page.waitForTimeout(1250);
  return errors;
}
test('fresh install renders without games, root calls, or hidden navigation', async ({ page }) => {
  const errors = await openApp(page, { ...snapshot, games: [] });
  await expect(page.getByRole('button', { name: 'Choose game folder', exact: true }).first()).toBeVisible();
  await expect(page.locator('#powerButton')).toBeHidden();
  expect(await page.evaluate(() => window.calls)).toEqual([]);
  expect(errors).toEqual([]);
});
test('favorites fit without excess scrolling; options and favorite changes work', async ({ page }) => {
  const errors = await openApp(page);
  await page.evaluate(() => showHomePanel(1));
  await page.waitForTimeout(600);
  await expect(page.locator('#homeFavorites .game-tile')).toHaveCount(2);
  expect(await page.locator('#homeFavorites').evaluate(el => el.scrollHeight - el.clientHeight)).toBeLessThanOrEqual(1);
  await page.locator('#homeFavorites .game-tile-actions').first().click();
  await page.waitForTimeout(550);
  await page.locator('#menuFavorite').click();
  await expect(page.locator('#homeFavorites .game-tile')).toHaveCount(1);
  await page.evaluate(() => showHomePanel(0));
  await page.waitForTimeout(600);
  expect(await page.locator('#homePages').evaluate(el => el.scrollTop)).toBe(0);
  expect(errors).toEqual([]);
});
test('library holds and scrolling never open the menu; a tile tap launches', async ({ page }) => {
  const errors = await openApp(page);
  await page.getByRole('button', { name: 'Library', exact: true }).click();
  const tile = page.locator('#libraryGrid .game-tile-play').first();
  await tile.dispatchEvent('pointerdown', { pointerId: 1, clientX: 50, clientY: 200 });
  await page.waitForTimeout(650);
  await tile.dispatchEvent('pointercancel', { pointerId: 1 });
  await tile.dispatchEvent('contextmenu');
  await expect(page.locator('#gameMenu')).toBeHidden();
  await tile.click();
  expect(await page.evaluate(() => window.calls.some(c => c[0] === 'play'))).toBe(true);
  expect(errors).toEqual([]);
});
test('performance selection, queued apply and root failure are represented accurately', async ({ page }) => {
  const errors = await openApp(page);
  await page.evaluate(() => { goPage('settings'); showSection('performance'); });
  const status = { available: true, active: 'balanced', request: 'status', cpu0: '500000', cpu1: '774000', gpu: '270000', gpuMax: '806000', cpu0max: '2000000', cpu1max: '2050000', temperature: '350' };
  await page.evaluate(s => receivePerformance(s), status);
  await page.locator('[data-mode="turbo"]').click();
  expect(await page.evaluate(() => window.calls.filter(c => c[0] === 'apply'))).toEqual([]);
  await page.evaluate(() => requestPerformance());
  await page.locator('#applyPerformance').click();
  await page.evaluate(s => receivePerformance(s), status);
  expect(await page.evaluate(() => window.calls.at(-1))).toEqual(['apply', 'turbo']);
  await page.evaluate(s => receivePerformance({ ...s, active: 'turbo', request: 'turbo' }), status);
  await expect(page.locator('#applyPerformance')).toHaveText('Applied');
  await page.evaluate(() => receivePerformance({ available: false, error: 'Root denied' }));
  await expect(page.locator('#applyPerformance')).toBeDisabled();
  await expect(page.locator('#retryPerformance')).toBeVisible();
  await expect(page.locator('#liveCpu')).toHaveText('—');
  expect(errors).toEqual([]);
});
test('blocked video pixel access preserves the usable interface', async ({ page }) => {
  const errors = await openApp(page);
  await page.evaluate(() => {
    Object.defineProperty(video, 'readyState', { value: 2 });
    Object.defineProperty(video, 'paused', { value: false });
    const ctx = videoCanvas.getContext('2d');
    ctx.drawImage = () => {};
    ctx.getImageData = () => { throw new DOMException('Blocked pixels', 'SecurityError'); };
    sampleVideo();
  });
  await expect(page.locator('#home')).toBeVisible();
  expect(errors).toEqual([]);
});
test('documentation screenshots', async ({ page }) => {
  test.skip(!process.env.NOMAD_SCREENSHOTS, 'Run npm run screenshots to refresh documentation images.');
  await openApp(page);
  fs.mkdirSync('docs/screenshots', { recursive: true });
  await page.screenshot({ path: 'docs/screenshots/home.png' });
  await page.evaluate(() => showHomePanel(1));
  await page.waitForTimeout(600);
  await page.screenshot({ path: 'docs/screenshots/favorites.png' });
  await page.evaluate(() => goPage('library'));
  await page.waitForTimeout(300);
  await page.screenshot({ path: 'docs/screenshots/library.png' });
});

test('playtime totals, ordering, last session and access guidance', async ({ page }) => {
  const errors = await openApp(page, { ...snapshot, usageAccess: true,
    games: snapshot.games.map((g, i) => ({ ...g, playtimeMs: i === 1 ? 7200000 : 0, lastSessionMs: i === 1 ? 1800000 : 0 })) });
  await page.evaluate(() => { goPage('settings'); showSection('playtime'); });
  await expect(page.locator('#totalPlaytime')).toHaveText('2h 0m');
  await expect(page.locator('#playtimeRows .row').first()).toContainText('Drift Circuit');
  await expect(page.locator('#playtimeRows .row').first()).toContainText('Last session: 30 min');
  await expect(page.locator('#trackingState')).toContainText('Enabled');
  await page.evaluate(() => receiveState({ ...fixture, usageAccess: false }));
  await expect(page.locator('#trackingState')).toContainText('Enable Android usage access');
  expect(errors).toEqual([]);
});
test('battery power, charge estimates, full, unplugged and unavailable states', async ({ page }) => {
  const errors = await openApp(page, { ...snapshot, battery: 80, charging: true, batteryStats: {
    flow: 'charging', watts: 5, ready: true, rate: 10, change: 5,
    observedMs: 1800000, remainingMs: 7200000, estimateSource: 'Android estimate',
  } });
  await page.locator('#battery').click();
  await expect(page.locator('#batteryPower')).toHaveText('5.0 W in');
  await expect(page.locator('#batteryRate')).toHaveText('+10.0% / hour');
  await expect(page.locator('#batteryEstimateLabel')).toHaveText('Estimated time to full');
  await expect(page.locator('#batteryRemaining')).toHaveText('About 2h 0m');
  await expect(page.locator('#batteryLost')).toHaveText('+5 percentage points');
  await page.waitForTimeout(600);
  await page.screenshot({ path: 'test-results/battery-charging.png' });
  await page.evaluate(() => receiveState({ ...fixture, batteryStats: {
    flow: 'discharging', watts: -3.2, ready: true, rate: 10, change: -5, remainingMs: 28800000,
  } }));
  await expect(page.locator('#batteryPower')).toHaveText('3.2 W out');
  await expect(page.locator('#batteryCharge')).toContainText('Discharging · Plugged in');
  await expect(page.locator('#batteryEstimateLabel')).toHaveText('Estimated time remaining');
  await expect(page.locator('#batteryRemaining')).toHaveText('About 8h 0m');
  await page.evaluate(() => receiveState({ ...fixture, battery: 100, batteryStats: { flow: 'full', watts: 0, remainingMs: -1 } }));
  await expect(page.locator('#batteryRemaining')).toHaveText('Fully charged');
  await page.evaluate(() => receiveState({ ...fixture, batteryStats: { flow: 'idle', watts: null, remainingMs: -1 } }));
  await expect(page.locator('#batteryRemaining')).toHaveText('—');
  await expect(page.locator('#batteryPower')).toHaveText('Unavailable on this device');
  await page.evaluate(() => receiveState({ ...fixture, charging: false, batteryStats: { flow: 'discharging', watts: -2, remainingMs: -1 } }));
  await expect(page.locator('#batteryHint')).toContainText('Learning');
  expect(errors).toEqual([]);
});

test('controller input moves focus, opens menus, favorites and goes back', async ({ page }) => {
  const errors = await openApp(page);
  // Home: left/right move the selected cover, A plays it.
  const first = await page.evaluate(() => current().id);
  await page.evaluate(() => deckInput('right'));
  expect(await page.evaluate(() => current().id)).not.toBe(first);
  await page.evaluate(() => deckInput('a'));
  expect(await page.evaluate(() => window.calls.at(-1)[0])).toBe('play');
  // R1 goes to the library with the first game focused; directions move between tiles.
  await page.evaluate(() => deckInput('r1'));
  await expect(page.locator('#library')).toHaveClass(/active/);
  const focused = () => page.evaluate(() => document.activeElement.closest('[data-id]')?.dataset.id);
  const start = await focused();
  expect(start).toBeTruthy();
  await page.evaluate(() => deckInput('right'));
  const next = await focused();
  expect(next).not.toBe(start);
  // X opens the game menu with its first entry focused; B closes it and restores the tile.
  await page.evaluate(() => deckInput('x'));
  await expect(page.locator('#gameMenu')).toBeVisible();
  expect(await page.evaluate(() => document.activeElement.id)).toBe('menuPlay');
  await page.evaluate(() => deckInput('b'));
  await expect(page.locator('#gameMenu')).toBeHidden();
  expect(await focused()).toBe(next);
  // Y favorites the focused game; Select opens Settings and L1/R1 step through its sections.
  const before = await page.evaluate(id => window.fixture.games.find(g => g.id === id).favorite, next);
  await page.evaluate(() => deckInput('y'));
  expect(await page.evaluate(id => window.fixture.games.find(g => g.id === id).favorite, next)).toBe(!before);
  await page.evaluate(() => deckInput('select'));
  await expect(page.locator('#settings')).toHaveClass(/active/);
  await page.evaluate(() => deckInput('r1'));
  expect(await page.evaluate(() => section)).toBe('artwork');
  await page.evaluate(() => { deckInput('l1'); deckInput('right'); });
  expect(await page.evaluate(() => !!document.activeElement.closest('.settings-pane'))).toBe(true);
  // Keyboard arrows drive the same navigation.
  await page.keyboard.press('Escape');
  await expect(page.locator('#home')).toHaveClass(/active/);
  expect(errors).toEqual([]);
});

test('settings refreshes keep controller focus; the second screen follows library focus', async ({ page }) => {
  await page.addInitScript(() => { window.secondScreen = []; });
  const errors = await openApp(page);
  await page.evaluate(() => { Deck.secondScreen = json => window.secondScreen.push(JSON.parse(json).title); });
  // Focus the third game's button in Artwork, a list that is rebuilt on every refresh.
  await page.evaluate(() => { deckInput('select'); showSection('artwork'); focusEl(document.querySelectorAll('#artworkRows button')[2]); });
  const where = () => page.evaluate(() => { const b = document.activeElement; return [b.closest('#artworkRows') ? [...document.querySelectorAll('#artworkRows button')].indexOf(b) : -1, b.textContent]; });
  const before = await where();
  const node = await page.evaluateHandle(() => document.activeElement);
  await page.evaluate(() => receiveState({ ...window.fixture, battery: 40 }));   // a refresh rebuilds the rows
  expect(await node.evaluate(n => n.isConnected)).toBe(false);                    // the old button is gone...
  expect(await where()).toEqual(before);                                         // ...and focus is on its replacement
  await page.evaluate(() => { deckInput('select'); deckInput('r1'); deckInput('right'); });
  const focusedTitle = await page.evaluate(() => allGames().find(g => g.id === document.activeElement.closest('[data-id]').dataset.id).title);
  expect(await page.evaluate(() => window.secondScreen.at(-1))).toBe(focusedTitle);
  expect(errors).toEqual([]);
});
