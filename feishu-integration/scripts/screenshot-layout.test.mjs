import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { prepareScreenshot, captureScreenshotParts, restoreScreenshot } from '../src/subscription-content.mjs'

test('dashboard and tab card screenshots preserve flex layout and live nodes', () => {
  // Use the packaged Chromium against synthetic HTML only; no BI/API access.
  const script = `
    import assert from 'node:assert/strict';
    import { chromium } from '/opt/apps/integration-worker/node_modules/playwright/index.mjs';
    const prepareScreenshot = ${prepareScreenshot.toString()};
    const restoreScreenshot = ${restoreScreenshot.toString()};
    const captureScreenshotParts = ${captureScreenshotParts.toString()};
    const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
    try {
      const page = await browser.newPage({ viewport: { width: 1680, height: 1050 }, deviceScaleFactor: 2 });
      await page.setContent('<style>body{margin:0}.shell{height:100vh;display:flex;flex-direction:column;overflow:hidden}.header{height:60px;flex:none}.layout{height:100%;min-height:0;display:flex;flex-direction:column;overflow:hidden}.canvas-container{position:relative;height:100%;min-height:0;flex:1 1 0;overflow:auto}.content{position:absolute;top:0;height:2400px;width:100%;background:linear-gradient(red,blue)}</style><div class="shell"><div class="header">Header</div><div class="layout"><div class="canvas-container"><div class="content">Dashboard</div></div></div></div>');
      const canvas = page.locator('.canvas-container');
      const original = await page.content();
      await canvas.evaluate(el => {
        for (let parent = el.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
          parent.style.overflow = 'visible'; parent.style.maxHeight = 'none'; parent.style.height = 'auto';
        }
      });
      assert.equal((await canvas.boundingBox()).height, 0, 'old ancestor expansion collapses the flex canvas');
      await page.setContent(original);
      const parentHeight = await page.locator('.layout').evaluate(el => el.getBoundingClientRect().height);
      await prepareScreenshot(canvas, false);
      assert.equal(await page.locator('.layout').evaluate(el => el.getBoundingClientRect().height), parentHeight);
      const image = await canvas.screenshot({ timeout: 5000 });
      assert.equal(image.readUInt32BE(16), 3360);
      assert.equal(image.readUInt32BE(20), 4800);
      const parts = await captureScreenshotParts(canvas);
      assert.equal(parts.length, 2);
      assert.equal(parts[0].readUInt32BE(20), 4000);
      assert.equal(parts[1].readUInt32BE(20), 800);
      assert.equal(parts[0].readUInt32BE(16), 3360);
      await page.setContent('<style>body{margin:0}.tab{position:relative;transform:translate(20px,20px);height:500px;overflow:hidden;--theme:blue}.card{position:absolute;width:600px;height:300px;background:var(--theme)}</style><div class="tab"><div class="card">Selected tab card</div></div>');
      const card = page.locator('.card');
      await prepareScreenshot(card, true);
      assert.equal(await page.locator('.tab > .card').count(), 1);
      const cardImage = await card.screenshot({ timeout: 5000 });
      assert.equal(cardImage.readUInt32BE(16), 3360);
      assert.equal(cardImage.readUInt32BE(20), 1960);
      await page.evaluate(() => { document.body.style.height = '5000px'; window.scrollTo(0, 3000); });
      const fixedParts = await captureScreenshotParts(card);
      assert.equal(fixedParts.length, 1);
      assert.equal(fixedParts[0].readUInt32BE(20), 1960);
      await card.evaluate(el => {
        const child = document.createElement('div'); child.id = 'table-test'; el.appendChild(child);
        child.__vueParentComponent = { exposed: { prepareSubscriptionScreenshot: async () => ({ containerId: child.id, contentHeight: 180 }) } };
      });
      await prepareScreenshot(card, true);
      assert.ok((await card.boundingBox()).height < 300, 'table content trims unused card height');
      await card.evaluate(el => {
        el.querySelector('#table-test').__vueParentComponent.exposed.prepareSubscriptionScreenshot = async () => ({ containerId: 'table-test', contentHeight: 300000 });
      });
      await prepareScreenshot(card, true);
      assert.equal((await card.boundingBox()).height, 980, 'thousands of rows stay within one screen');
      const largeTableParts = await captureScreenshotParts(card);
      assert.equal(largeTableParts.length, 1, 'large tables never become a stitched long image');
      await restoreScreenshot(card);
      assert.equal((await card.boundingBox()).height, 300);
      assert.equal(await page.locator('.tab').evaluate(el => el.style.cssText), '', 'ancestor layout restored for subsequent cards');
      console.log('2x PNG dimensions verified; card remains mounted; short table whitespace removed');
    } finally { await browser.close(); }
  `
  const container = process.env.BI_TEST_CONTAINER || 'dataease-gate1'
  const result = spawnSync('docker', ['exec', '-i', container, 'node', '--input-type=module'], {
    input: script, encoding: 'utf8', timeout: 30000
  })
  assert.equal(result.status, 0, result.error?.message || result.stderr)
  console.log(result.stdout.trim())
})
