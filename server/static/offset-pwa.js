/* OFFSET iOS web app registration. */
if ('serviceWorker' in navigator && window.isSecureContext) {
  window.addEventListener('load', () => navigator.serviceWorker.register('/offset-sw.js', {scope:'/'}).catch(()=>{}));
}
