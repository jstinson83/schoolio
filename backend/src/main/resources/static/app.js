// One shared file, per-page blocks gated on a DOM element that only exists
// on that page - same convention as foodie's app.js.

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  });
}

// Nav bar (nav.ftl) - present on every page behind the nav include, so this
// block just no-ops (via the early return) on splash.ftl, which doesn't
// include nav.ftl.
(function () {
  const toggle = document.getElementById('navToggle');
  const menu = document.getElementById('navMenu');
  if (!toggle || !menu) return;

  // Menu stays in the DOM (not [hidden]) while the max-height/opacity
  // transition in base.css runs, then gets [hidden] again once it's fully
  // closed so it's out of the tab order and a11y tree at rest.
  let closeTimer = null;

  function openMenu() {
    clearTimeout(closeTimer);
    menu.hidden = false;
    // Force a layout flush so the browser sees the collapsed state before
    // the open class flips it - otherwise the two styles land in the same
    // frame and there's nothing to transition from.
    void menu.offsetHeight;
    menu.classList.add('is-open');
    toggle.classList.add('is-open');
    toggle.setAttribute('aria-expanded', 'true');
  }

  function closeMenu() {
    menu.classList.remove('is-open');
    toggle.classList.remove('is-open');
    toggle.setAttribute('aria-expanded', 'false');
    clearTimeout(closeTimer);
    closeTimer = setTimeout(() => {
      menu.hidden = true;
    }, 220);
  }

  toggle.addEventListener('click', () => {
    if (menu.classList.contains('is-open')) {
      closeMenu();
    } else {
      openMenu();
    }
  });
})();

// Inbox page (inbox.ftl) - poll while a Gmail pull is still syncing or any
// message is still PENDING. Action items are grouped by date server-side
// (see InboxRoutes.kt's buildDateGroups), so there's no single per-message
// DOM node left to patch in place the way this used to - instead, just
// reload once both are done, which re-renders the page with whatever new
// mail/finished processing already correctly grouped.
(function () {
  const processingBanner = document.getElementById('processingBanner');
  if (!processingBanner) return;

  const bannerText = processingBanner.querySelector('.processing-banner-text');

  const inboxPoll = setInterval(async () => {
    try {
      const res = await fetch('/inbox/status');
      const data = await res.json();
      if (!data.syncing && data.pending === 0) {
        clearInterval(inboxPoll);
        window.location.reload();
        return;
      }
      if (bannerText) {
        bannerText.textContent = data.syncing
          ? 'Checking your inbox for new mail…'
          : (data.pending === 1 ? 'Processing 1 message…' : `Processing ${data.pending} messages…`);
      }
    } catch (e) {
      // Best effort - a failed poll just tries again next tick.
    }
  }, 3000);
})();
