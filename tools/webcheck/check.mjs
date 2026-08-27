// Drives the web app in a real Chromium (Edge) and reports anything it finds:
// console errors, unhandled rejections, failed requests, whether the AudioWorklet
// actually starts, and screenshots of both layouts.
//
//   node tools/webcheck/check.mjs [baseUrl]
//
// Not part of the deployment - a development check only.

import { mkdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const here = dirname(fileURLToPath(import.meta.url));
const shots = join(here, 'shots');
mkdirSync(shots, { recursive: true });

const BASE = process.argv[2] || 'http://127.0.0.1:8777/';

const EDGE_CANDIDATES = [
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
];
const executablePath = EDGE_CANDIDATES.find((p) => existsSync(p));
if (!executablePath) {
  console.error('No Chromium-based browser found. Edit EDGE_CANDIDATES.');
  process.exit(1);
}

const problems = [];

const browser = await puppeteer.launch({
  executablePath,
  headless: true,
  args: [
    '--autoplay-policy=no-user-gesture-required',
    '--mute-audio',
    '--no-sandbox',
    '--disable-gpu',
  ],
});

const page = await browser.newPage();

page.on('console', (msg) => {
  if (msg.type() === 'error' || msg.type() === 'warning') {
    problems.push(`console.${msg.type()}: ${msg.text()}`);
  }
});
page.on('pageerror', (err) => problems.push(`pageerror: ${err.message}`));
page.on('requestfailed', (req) => {
  problems.push(`requestfailed: ${req.url()} (${req.failure()?.errorText})`);
});
page.on('response', (res) => {
  if (res.status() >= 400) problems.push(`http ${res.status()}: ${res.url()}`);
});

async function shot(name) {
  await page.screenshot({ path: join(shots, `${name}.png`) });
}

// ---------------------------------------------------------------- phone ----
await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
await page.goto(BASE, { waitUntil: 'load' });
await page.waitForSelector('.play-btn', { timeout: 10000 });

const layoutPhone = await page.evaluate(() => ({
  tabbarVisible: getComputedStyle(document.querySelector('.tabbar')).display !== 'none',
  topTabsVisible: getComputedStyle(document.querySelector('.topbar-tabs')).display !== 'none',
  sa: document.querySelector('.sa-note')?.textContent,
  detail: document.querySelector('.sa-detail')?.textContent,
  cards: document.querySelectorAll('.panel .card').length,
  strip: document.querySelectorAll('.pitch-cell').length,
  canvasPainted: (() => {
    const c = document.querySelector('canvas');
    return c ? c.width > 0 && c.height > 0 : false;
  })(),
}));
await shot('phone-player');

// Start audio and confirm the worklet really came up.
await page.click('.play-btn');
await new Promise((r) => setTimeout(r, 2500));

const audioState = await page.evaluate(() => {
  const btn = document.querySelector('.play-btn');
  return {
    playing: btn.classList.contains('is-playing'),
    // The meter only ticks if the worklet is running on the audio thread.
    level: window.__tanpuraProbe ? window.__tanpuraProbe() : null,
  };
});
await shot('phone-playing');

// Every tab must build without throwing.
const tabResults = {};
for (const tab of ['tuner', 'audio', 'settings', 'player']) {
  // Both bars carry [data-tab]; on a phone only the bottom one is visible.
  await page.click(`.tabbar [data-tab="${tab}"]`);
  await new Promise((r) => setTimeout(r, 350));
  tabResults[tab] = await page.evaluate(() => document.querySelectorAll('.panel .card').length);
  if (tab !== 'player') await shot(`phone-${tab}`);
}

// Modals.
await page.evaluate(() => document.querySelector('.transport .pill').click());
await new Promise((r) => setTimeout(r, 400));
const presetsOpen = await page.evaluate(() => !!document.querySelector('.modal-sheet'));
await shot('phone-presets');
await page.evaluate(() => document.querySelector('.modal .icon-btn')?.click());
await new Promise((r) => setTimeout(r, 300));

// Exercise the sliders and chips the way a finger would.
const interaction = await page.evaluate(() => {
  const out = {};
  const chip = document.querySelectorAll('.chips .chip')[3];
  if (chip) {
    chip.click();
    out.chipLabel = chip.textContent;
  }
  const sliders = document.querySelectorAll('input[type=range]');
  out.sliderCount = sliders.length;
  for (const s of sliders) {
    s.value = String((parseFloat(s.min) + parseFloat(s.max)) / 2);
    s.dispatchEvent(new Event('input', { bubbles: true }));
  }
  out.saAfter = document.querySelector('.sa-note')?.textContent;
  return out;
});
await new Promise((r) => setTimeout(r, 300));
await shot('phone-after-interaction');

// -------------------------------------------------------------- laptop ----
await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 });
await new Promise((r) => setTimeout(r, 500));
const layoutDesktop = await page.evaluate(() => {
  const app = document.querySelector('#app');
  const stage = document.querySelector('.stage').getBoundingClientRect();
  const panel = document.querySelector('.panel').getBoundingClientRect();
  return {
    display: getComputedStyle(app).display,
    columns: getComputedStyle(app).gridTemplateColumns,
    tabbarVisible: getComputedStyle(document.querySelector('.tabbar')).display !== 'none',
    topTabsVisible: getComputedStyle(document.querySelector('.topbar-tabs')).display !== 'none',
    // Side by side means the panel starts to the right of the stage.
    sideBySide: panel.left >= stage.right - 2,
    stageWidth: Math.round(stage.width),
    panelWidth: Math.round(panel.width),
  };
});
await shot('desktop-player');

await page.click('.topbar-tabs [data-tab="tuner"]');
await new Promise((r) => setTimeout(r, 400));
await shot('desktop-tuner');

// Keyboard shortcuts are a desktop-only affordance.
await page.click('.topbar-tabs [data-tab="player"]');
await new Promise((r) => setTimeout(r, 300));
const beforeKeys = await page.evaluate(() => document.querySelector('.sa-note').textContent);
await page.keyboard.press('ArrowRight');
await page.keyboard.press('ArrowRight');
await new Promise((r) => setTimeout(r, 200));
const afterKeys = await page.evaluate(() => document.querySelector('.sa-note').textContent);

// Wide layout: two columns of cards.
await page.setViewport({ width: 1600, height: 1000, deviceScaleFactor: 1 });
await new Promise((r) => setTimeout(r, 400));
const wide = await page.evaluate(() => ({
  panelColumns: getComputedStyle(document.querySelector('.panel')).gridTemplateColumns,
}));
await shot('desktop-wide');

await browser.close();

// --------------------------------------------------------------- report ----
console.log('\n=== phone layout ===');
console.log(layoutPhone);
console.log('\n=== audio ===');
console.log(audioState);
console.log('\n=== tabs (card counts) ===');
console.log(tabResults);
console.log('presets modal opened:', presetsOpen);
console.log('\n=== interaction ===');
console.log(interaction);
console.log('\n=== desktop layout ===');
console.log(layoutDesktop);
console.log('keyboard: Sa', beforeKeys, '->', afterKeys);
console.log('\n=== wide layout ===');
console.log(wide);

console.log('\n=== problems ===');
if (problems.length === 0) {
  console.log('none');
} else {
  for (const p of [...new Set(problems)]) console.log('-', p);
}
console.log(`\nscreenshots in ${shots}`);
process.exit(problems.length ? 1 : 0);
