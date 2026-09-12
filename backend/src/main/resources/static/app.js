// One shared file, per-page blocks gated on a DOM element that only exists
// on that page - same convention as foodie's app.js.

// Nav bar (nav.ftl) - present on every page behind the nav include, so this
// block just no-ops (via the early return) on splash.ftl, which doesn't
// include nav.ftl.
(function () {
  const toggle = document.getElementById('navToggle');
  const menu = document.getElementById('navMenu');
  if (!toggle || !menu) return;

  toggle.addEventListener('click', () => {
    const open = menu.hidden;
    menu.hidden = !open;
    toggle.setAttribute('aria-expanded', String(open));
  });
})();

// Inbox page (inbox.ftl) - poll while any message is still PENDING. Action
// items are grouped by date server-side (see InboxRoutes.kt's
// buildDateGroups), so there's no single per-message DOM node left to patch
// in place the way this used to - instead, just reload once nothing's
// pending, which re-renders the page with whatever finished processing
// already correctly grouped.
(function () {
  const processingBanner = document.getElementById('processingBanner');
  if (!processingBanner) return;

  const bannerText = processingBanner.querySelector('.processing-banner-text');

  const inboxPoll = setInterval(async () => {
    try {
      const res = await fetch('/inbox/status');
      const data = await res.json();
      if (data.pending === 0) {
        clearInterval(inboxPoll);
        window.location.reload();
        return;
      }
      if (bannerText) {
        bannerText.textContent = data.pending === 1 ? 'Processing 1 message…' : `Processing ${data.pending} messages…`;
      }
    } catch (e) {
      // Best effort - a failed poll just tries again next tick.
    }
  }, 3000);
})();
