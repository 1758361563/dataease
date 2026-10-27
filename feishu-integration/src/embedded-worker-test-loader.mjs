// Test-only module replacement. Never packaged in the BI runtime image.
export async function resolve(specifier, context, nextResolve) {
  if (specifier === './feishu.mjs') return { url: 'bi-fixture:feishu', shortCircuit: true }
  if (specifier === './capture.mjs') return { url: 'bi-fixture:capture', shortCircuit: true }
  return nextResolve(specifier, context)
}
export async function load(url, context, nextLoad) {
  if (url === 'bi-fixture:feishu') return { format: 'module', shortCircuit: true, source: `
    let counter = 0;
    export async function getTenantAccessToken() { return { token:'fixture-token', meta:{expire:7200} }; }
    export async function uploadImage() { return { ok:true, imageKey:'fixture-image', bytes:1 }; }
    export async function sendImage() { return { ok:true, http:200, messageId:'fixture-message-'+(++counter) }; }
  ` }
  if (url === 'bi-fixture:capture') return { format: 'module', shortCircuit: true, source: `
    import fs from 'node:fs'; import path from 'node:path';
    export async function captureCard() {
      const pngPath = path.join(process.env.FEISHU_TMP_DIR, 'fixture.png');
      fs.mkdirSync(path.dirname(pngPath),{recursive:true}); fs.writeFileSync(pngPath,Buffer.from([137,80,78,71]));
      return {pngPath,meta:{pngBytes:4,pngHash:'fixture',filterMode:'default',durationMs:1}};
    }
  ` }
  return nextLoad(url, context)
}
