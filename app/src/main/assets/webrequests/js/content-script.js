// content-script.js — runs in every page
// Scans the DOM for image URLs (including those served from service-worker
// cache, which webRequest cannot see) and forwards them to the background.

console.log('[cs] loaded', location.href);

(() => {
  const seen = new Set();
  const BATCH_MS = 200;
  let pending = [];
  let flushTimer = null;

  function flush() {
    flushTimer = null;
    if (pending.length === 0) return;
    const batch = pending;
    pending = [];
    console.log('[cs] sending batch of', batch.length);
    try {
      const p = browser.runtime.sendMessage({ kind: 'images-detected', urls: batch });
      if (p && p.catch) p.catch((e) => console.log('[cs] send rejected:', e?.message));
    } catch (e) {
      console.log('[cs] send threw:', e?.message);
    }
  }

  function queue(url) {
    if (!url || seen.has(url)) return;
    if (!/^https?:/i.test(url)) return;
    seen.add(url);
    pending.push(url);
    if (!flushTimer) flushTimer = setTimeout(flush, BATCH_MS);
  }

  function reportImg(img) {
    if (!img) return;
    queue(img.currentSrc || img.src);
  }

  function reportSource(source) {
    if (!source) return;
    const srcset = source.srcset || source.getAttribute('srcset');
    if (srcset) {
      srcset.split(',').forEach((part) => {
        const url = part.trim().split(/\s+/)[0];
        if (url) queue(url);
      });
    }
    if (source.src) queue(source.src);
  }

  function scan(root) {
    if (!root || root.nodeType !== 1) return;
    if (root.tagName === 'IMG') reportImg(root);
    else if (root.tagName === 'SOURCE') reportSource(root);
    if (root.querySelectorAll) {
      root.querySelectorAll('img').forEach(reportImg);
      root.querySelectorAll('source').forEach(reportSource);
    }
  }

  // Initial scan
  scan(document.documentElement);

  // Re-scan at key lifecycle events
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => scan(document.documentElement));
  }
  window.addEventListener('load', () => scan(document.documentElement));

  // Watch for DOM changes
  const mo = new MutationObserver((mutations) => {
    for (const m of mutations) {
      if (m.type === 'childList') {
        m.addedNodes.forEach(scan);
      } else if (m.type === 'attributes') {
        const t = m.target;
        if (t.tagName === 'IMG') reportImg(t);
        else if (t.tagName === 'SOURCE') reportSource(t);
      }
    }
  });

  function startObserver() {
    const target = document.body || document.documentElement;
    if (!target) {
      setTimeout(startObserver, 50);
      return;
    }
    mo.observe(target, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ['src', 'srcset'],
    });
  }
  startObserver();

  // Catch images that finish loading after insertion (lazy load)
  document.addEventListener('load', (e) => {
    if (e.target && e.target.tagName === 'IMG') reportImg(e.target);
  }, true);

  console.log('[cs] setup complete');
})();