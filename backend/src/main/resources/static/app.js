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

// Photo-import page (import-photo.ftl) - the FAB is a speed dial (a "+" that
// expands into "Take a photo" / "Choose a file") rather than opening a
// picker directly, since a single hidden input with both `capture` and a
// plain gallery pick isn't reliably offered as a choice across mobile
// browsers - two separate inputs (one with `capture="environment"`, one
// without) makes the choice explicit instead of leaving it to whatever a
// given browser happens to default to. Each photo's
// POST /inbox/import-photo/extract response is appended into #eventsList in
// place, so several photos build up one review batch before the confirm
// form at the bottom is ever submitted (see InboxRoutes.kt's doc comment on
// that route for why it's fetch()-driven instead of a plain form post).
// #confirmForm's own submit is a normal full-page POST, same as every other
// form in this app - only the per-photo extraction step needs JS at all.
(function () {
  const fabButton = document.getElementById('fabButton');
  if (!fabButton) return;

  const fabContainer = document.getElementById('fabContainer');
  const fabMenu = document.getElementById('fabMenu');
  const cameraOption = document.getElementById('cameraOption');
  const libraryOption = document.getElementById('libraryOption');
  const cameraInput = document.getElementById('cameraInput');
  const libraryInput = document.getElementById('libraryInput');
  const stagingCard = document.getElementById('stagingCard');
  const stagingName = document.getElementById('stagingName');
  const stagingStatus = document.getElementById('stagingStatus');
  const stagingCancel = document.getElementById('stagingCancel');
  const extractBtn = document.getElementById('extractBtn');
  const importError = document.getElementById('importError');
  const emptyState = document.getElementById('emptyState');
  const eventsList = document.getElementById('eventsList');
  const eventCount = document.getElementById('eventCount');
  const confirmBar = document.getElementById('confirmBar');

  // The staged File object itself, not "whichever input still has a value" -
  // simpler than tracking which of the two inputs was last used, since only
  // one photo is ever staged at a time.
  let currentFile = null;

  // Bumped for every row ever added this page visit, never reused - even
  // across several photos - so each row's form field names (title_0,
  // title_1, ...) stay unique for #confirmForm's eventual submit.
  let nextIndex = 0;

  function updateChrome() {
    const hasEvents = eventsList.children.length > 0;
    emptyState.hidden = hasEvents;
    confirmBar.hidden = !hasEvents;
    eventCount.value = String(nextIndex);
  }

  // The row's skeleton is a fixed template (index is a number we generate
  // ourselves, never user data) - each field's actual value is set via the
  // .value property afterward instead of interpolated into the HTML string,
  // so an extracted title/notes containing a quote or angle bracket (a
  // plausible OCR read of arbitrary handwriting) can't break out of an
  // attribute or inject markup the way string-building the whole tag would.
  function addEventRow(event) {
    const index = nextIndex++;
    const li = document.createElement('li');
    li.className = 'action-item photo-review-item just-added';
    li.innerHTML = `
      <label class="photo-review-include">
        <input type="checkbox" name="include_${index}" checked>
        Add this event
      </label>
      <label>Title
        <input type="text" name="title_${index}" required>
      </label>
      <div class="photo-review-date-time">
        <label>Date
          <input type="date" name="date_${index}">
        </label>
        <label>Time (optional)
          <input type="time" name="time_${index}">
        </label>
      </div>
      <label>Notes (optional)
        <input type="text" name="description_${index}">
      </label>
    `;
    li.querySelector(`input[name="title_${index}"]`).value = event.title;
    li.querySelector(`input[name="date_${index}"]`).value = event.date;
    li.querySelector(`input[name="time_${index}"]`).value = event.time || '';
    li.querySelector(`input[name="description_${index}"]`).value = event.description || '';
    eventsList.appendChild(li);
    li.addEventListener('animationend', () => li.classList.remove('just-added'), { once: true });
  }

  function showError(message) {
    importError.textContent = message;
    importError.hidden = false;
  }

  function openMenu() {
    fabMenu.hidden = false;
    fabButton.setAttribute('aria-expanded', 'true');
  }

  function closeMenu() {
    fabMenu.hidden = true;
    fabButton.setAttribute('aria-expanded', 'false');
  }

  fabButton.addEventListener('click', () => {
    if (fabMenu.hidden) openMenu(); else closeMenu();
  });

  // Closes the speed dial on an outside click/tap or Escape - the two
  // options are the only affordance while it's open, so anything else the
  // user does should just dismiss it rather than leaving it stuck open.
  document.addEventListener('click', (e) => {
    if (!fabMenu.hidden && !fabContainer.contains(e.target)) closeMenu();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !fabMenu.hidden) closeMenu();
  });

  cameraOption.addEventListener('click', () => {
    closeMenu();
    cameraInput.click();
  });

  libraryOption.addEventListener('click', () => {
    closeMenu();
    libraryInput.click();
  });

  function onFileChosen(file) {
    if (!file) return;
    currentFile = file;
    importError.hidden = true;
    stagingName.textContent = file.name || 'calendar-photo.jpg';
    stagingStatus.textContent = 'Ready to extract';
    extractBtn.disabled = false;
    extractBtn.textContent = 'Extract events';
    stagingCard.hidden = false;
    stagingCard.scrollIntoView({ block: 'nearest' });
  }

  cameraInput.addEventListener('change', () => onFileChosen(cameraInput.files && cameraInput.files[0]));
  libraryInput.addEventListener('change', () => onFileChosen(libraryInput.files && libraryInput.files[0]));

  function resetStaging() {
    currentFile = null;
    cameraInput.value = '';
    libraryInput.value = '';
    stagingCard.hidden = true;
  }

  stagingCancel.addEventListener('click', resetStaging);

  extractBtn.addEventListener('click', async () => {
    if (!currentFile) return;
    extractBtn.disabled = true;
    extractBtn.textContent = 'Extracting…';
    stagingStatus.textContent = 'Reading the photo…';
    importError.hidden = true;

    try {
      const formData = new FormData();
      formData.append('photo', currentFile);
      const res = await fetch('/inbox/import-photo/extract', { method: 'POST', body: formData });
      const data = await res.json();
      if (data.error) {
        showError(data.error);
      } else {
        data.events.forEach(addEventRow);
        updateChrome();
      }
    } catch (e) {
      showError("Couldn't reach the server - check your connection and try again.");
    } finally {
      resetStaging();
    }
  });

  updateChrome();
})();
