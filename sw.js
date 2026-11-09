const CACHE='coffre-v23';
const ASSETS=['./','./index.html','./manifest.webmanifest','./icon-192.png','./icon-512.png','./pdfjs/pdf.min.mjs','./pdfjs/pdf.worker.min.mjs'];
// lecteur de PDF : polices et décodeurs gardés sur le téléphone pour lire sans internet
const EXTRA=['./pdfjs/wasm/openjpeg.wasm','./pdfjs/wasm/jbig2.wasm','./pdfjs/wasm/qcms_bg.wasm','./pdfjs/wasm/openjpeg_nowasm_fallback.js','./pdfjs/wasm/jbig2_nowasm_fallback.js',
  './pdfjs/standard_fonts/FoxitDingbats.pfb','./pdfjs/standard_fonts/FoxitFixed.pfb','./pdfjs/standard_fonts/FoxitFixedBold.pfb','./pdfjs/standard_fonts/FoxitFixedBoldItalic.pfb','./pdfjs/standard_fonts/FoxitFixedItalic.pfb',
  './pdfjs/standard_fonts/FoxitSerif.pfb','./pdfjs/standard_fonts/FoxitSerifBold.pfb','./pdfjs/standard_fonts/FoxitSerifBoldItalic.pfb','./pdfjs/standard_fonts/FoxitSerifItalic.pfb','./pdfjs/standard_fonts/FoxitSymbol.pfb',
  './pdfjs/standard_fonts/LiberationSans-Bold.ttf','./pdfjs/standard_fonts/LiberationSans-BoldItalic.ttf','./pdfjs/standard_fonts/LiberationSans-Italic.ttf','./pdfjs/standard_fonts/LiberationSans-Regular.ttf'];
self.addEventListener('install',e=>{ e.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS).then(()=>Promise.all(EXTRA.map(u=>c.add(u).catch(()=>{})))))); self.skipWaiting(); });
self.addEventListener('activate',e=>{ e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())); });
self.addEventListener('fetch',e=>{
  if(e.request.method!=='GET') return;
  e.respondWith(fetch(e.request.url,{cache:'no-store'}).then(r=>{ const cp=r.clone(); caches.open(CACHE).then(c=>c.put(e.request,cp)); return r; })
    .catch(()=>caches.match(e.request,{ignoreSearch:true}).then(m=>m||caches.match('./index.html'))));
});
