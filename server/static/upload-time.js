/* Live, human-readable completed upload timestamps. Uses the server's
 * verified clip.created (Unix seconds), never a device-reported clock.
 */
(() => {
  'use strict';
  const format = (timestamp, nowSeconds = Date.now()/1000) => {
    const seconds = Number(timestamp);
    if (!Number.isFinite(seconds) || seconds <= 0) return 'Upload time unavailable';
    const elapsed = Math.max(0, Math.floor(nowSeconds - seconds));
    if (elapsed < 5) return 'Uploaded just now';
    if (elapsed < 60) return 'Uploaded '+elapsed+' sec ago';
    const minutes = Math.floor(elapsed / 60);
    if (minutes < 60) return 'Uploaded '+minutes+' min ago';
    const hours = Math.floor(elapsed / 3600);
    if (hours < 24) return 'Uploaded '+hours+' hr ago';
    const days = Math.floor(elapsed / 86400);
    if (days < 30) return 'Uploaded '+days+' '+(days===1?'day':'days')+' ago';
    const months = Math.floor(days / 30);
    if (months < 12) return 'Uploaded '+months+' '+(months===1?'month':'months')+' ago';
    const years = Math.floor(days / 365);
    return 'Uploaded '+years+' '+(years===1?'year':'years')+' ago';
  };
  const update = () => {
    document.querySelectorAll('[data-uploaded-at]').forEach(element => {
      const seconds = Number(element.getAttribute('data-uploaded-at'));
      element.textContent = format(seconds);
      if (Number.isFinite(seconds) && seconds > 0) {
        const iso = new Date(seconds*1000);
        element.title = 'Uploaded '+iso.toLocaleString('en-GB', {
          year:'numeric',month:'short',day:'numeric',hour:'2-digit',
          minute:'2-digit',timeZoneName:'short'
        });
      }
    });
  };
  window.OffsetUploadTime = {format, update};
  document.addEventListener('DOMContentLoaded', () => {
    update();
    setInterval(() => {
      if (!document.hidden) update();
    }, 10000);
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) update();
    });
  });
})();
