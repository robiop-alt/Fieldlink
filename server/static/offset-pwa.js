/* OFFSET iOS web app registration. */
if ('serviceWorker' in navigator && window.isSecureContext) {
  window.addEventListener('load', () => navigator.serviceWorker.register('/offset-sw.js', {scope:'/'}).catch(()=>{}));
}

/* Pull to refresh for the installed iPhone/iPad web app. */
(() => {
  const installed = window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
  const ios = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  if (!installed || !ios) return;

  const start = () => {
    const style = document.createElement('style');
    style.textContent = `
      html.offset-pull-enabled,html.offset-pull-enabled body{overscroll-behavior-y:contain}
      .offset-pull-refresh{position:fixed;top:calc(env(safe-area-inset-top,0px) + 8px);left:50%;transform:translate(-50%,-160%);opacity:0;z-index:10000;pointer-events:none;padding:10px 16px;border:1px solid #354b3b;border-radius:24px;background:#141c18;color:#dce7df;font:600 12px/1.3 system-ui,sans-serif;box-shadow:0 4px 18px #0005;transition:opacity .12s,transform .12s;white-space:nowrap}
      .offset-pull-refresh.visible{opacity:1;transform:translate(-50%,0)}
    `;
    document.head.append(style);
    document.documentElement.classList.add('offset-pull-enabled');
    const hint = document.createElement('div');
    hint.className = 'offset-pull-refresh';
    hint.setAttribute('role', 'status');
    hint.setAttribute('aria-live', 'polite');
    document.body.append(hint);
    const threshold = 80;
    let origin = null, distance = 0, refreshing = false, ready = false;
    const reset = () => {
      origin = null;
      distance = 0;
      ready = false;
      if (!refreshing) hint.classList.remove('visible');
    };
    const atTop = () => (window.scrollY || document.documentElement.scrollTop || document.body.scrollTop || 0) <= 0;
    const blocked = target => {
      if (document.fullscreenElement || document.webkitFullscreenElement || document.querySelector('dialog[open]')) return true;
      if (!(target instanceof Element)) return true;
      if (target.closest('video,audio,input,textarea,select,button,a,[contenteditable]:not([contenteditable="false"]),[role="slider"],[role="dialog"],.offset-player')) return true;
      for (let el = target; el && el !== document.body && el !== document.documentElement; el = el.parentElement) {
        if (el.scrollHeight > el.clientHeight + 1 && /(auto|scroll)/.test(getComputedStyle(el).overflowY)) return true;
      }
      return false;
    };
    document.addEventListener('touchstart', event => {
      reset();
      if (refreshing || event.touches.length !== 1 || !atTop() || blocked(event.target)) return;
      origin = {x:event.touches[0].clientX,y:event.touches[0].clientY};
    }, {passive:true});
    document.addEventListener('touchmove', event => {
      if (!origin || refreshing) return;
      if (event.touches.length !== 1 || !atTop()) { reset(); return; }
      const dx = event.touches[0].clientX - origin.x;
      const dy = event.touches[0].clientY - origin.y;
      if (dy < 0 || Math.abs(dx) > 24 && Math.abs(dx) > dy) { reset(); return; }
      if (dy < 10) return;
      if (!event.cancelable) { reset(); return; }
      event.preventDefault();
      distance = dy;
      const nextReady = distance >= threshold;
      if (!hint.classList.contains('visible') || nextReady !== ready) {
        hint.textContent = nextReady ? 'Release to refresh' : 'Pull down to refresh';
      }
      ready = nextReady;
      hint.classList.add('visible');
    }, {passive:false});
    document.addEventListener('touchend', () => {
      if (!origin || refreshing) return;
      if (ready && distance >= threshold && atTop()) {
        refreshing = true;
        hint.textContent = 'Refreshing…';
        hint.classList.add('visible');
        requestAnimationFrame(() => requestAnimationFrame(() => window.location.reload()));
      } else reset();
    }, {passive:true});
    document.addEventListener('touchcancel', reset, {passive:true});
    window.addEventListener('pagehide', reset);
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, {once:true});
  else start();
})();
