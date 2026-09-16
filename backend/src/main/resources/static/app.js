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
      // A reload here would silently wipe out anything sitting in the
      // photo-import review list below (see that block's own IIFE) - it's
      // only ever in the DOM, never persisted, until #confirmForm is
      // submitted. Skip the reload while there's something staged there;
      // the next tick retries once it's confirmed or discarded.
      const eventsList = document.getElementById('eventsList');
      const hasUnreviewedPhotoEvents = eventsList && eventsList.children.length > 0;
      if (!data.syncing && data.pending === 0 && !hasUnreviewedPhotoEvents) {
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

// Inline date editing on inbox action-item cards (inbox.ftl) - tapping the
// .action-due pill swaps it for its sibling .date-edit-form's native date
// input and opens the picker immediately, so picking a new date is the only
// step; there's no separate confirm button, the input's own change event
// submits the form. A plain full-page form submit (not fetch) is deliberate
// - the item needs to regroup under its new date heading server-side (see
// InboxRoutes.kt's POST .../date), which a normal reload already gives for
// free. Blurring without picking a different date reverts to the pill
// instead of submitting a no-op post.
(function () {
  const dateButtons = document.querySelectorAll('.action-due[data-action="edit-date"]');
  if (dateButtons.length === 0) return;

  dateButtons.forEach((button) => {
    const form = button.nextElementSibling;
    const input = form.querySelector('input[name="date"]');
    const originalValue = input.value;

    function openEditor() {
      button.hidden = true;
      form.hidden = false;
      input.focus();
      // Not every browser supports showPicker() (or allows it outside a
      // direct user gesture) - focusing the input is still a usable fallback
      // (it opens the native picker on its own in most mobile browsers).
      if (input.showPicker) {
        try { input.showPicker(); } catch (e) { /* unsupported here - focus() above still works */ }
      }
    }

    function closeEditor() {
      form.hidden = true;
      button.hidden = false;
    }

    button.addEventListener('click', openEditor);

    input.addEventListener('change', () => {
      if (input.value && input.value !== originalValue) form.submit();
    });

    input.addEventListener('blur', () => {
      if (input.value === originalValue) closeEditor();
    });
  });
})();

// Photo-import FAB on the main inbox page (inbox.ftl) - lives right on
// /inbox rather than a separate page, since there's no reason to navigate
// away just to add a photo. It's a speed dial (a "+" that expands into
// "Take a photo" / "Choose a file") rather than opening a picker directly,
// since a single hidden input with both `capture` and a plain gallery pick
// isn't reliably offered as a choice across mobile browsers - two separate
// inputs (one with `capture="environment"`, one without) makes the choice
// explicit instead of leaving it to whatever a given browser happens to
// default to. Picking a photo (either input) extracts it immediately - no
// separate "Extract events" click, since there's nothing to configure
// first - and each photo's POST /inbox/import-photo/extract response is
// appended into #eventsList in place, so several photos build up one
// review batch before the confirm form at the bottom is ever submitted
// (see InboxRoutes.kt's doc comment on that route for why it's
// fetch()-driven instead of a plain form post). #confirmForm's own submit
// is a normal full-page POST, same as every other form in this app - only
// the per-photo extraction step needs JS at all.
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
  const importError = document.getElementById('importError');
  const photoReviewSection = document.getElementById('photoReviewSection');
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

  // #photoReviewSection stays hidden entirely until the first row lands -
  // unlike the old standalone page, /inbox already has its own empty/loading
  // states (the processing banner, "No messages found.", etc.), so there's
  // no separate empty-state placeholder to show here while nothing's staged.
  function updateChrome() {
    const hasEvents = eventsList.children.length > 0;
    photoReviewSection.hidden = !hasEvents;
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

  // Set only while a fetch is actually in flight - lets stagingCancel tell
  // "abort the request" apart from "just dismiss the card" (nothing to abort
  // once it's already resolved).
  let currentAbortController = null;

  function resetStaging() {
    currentFile = null;
    currentAbortController = null;
    cameraInput.value = '';
    libraryInput.value = '';
    stagingCard.hidden = true;
  }

  // No separate "Extract events" step - picking a file (via either input,
  // libraryInput also accepting a PDF or Word document, not just an image)
  // starts the extraction immediately, since there's nothing for a household
  // member to configure first; the staging card here is purely a progress
  // indicator (with a cancel) while Gemini reads it, not a
  // confirm-before-you-start prompt.
  async function onFileChosen(file) {
    if (!file) return;
    currentFile = file;
    importError.hidden = true;
    stagingName.textContent = file.name || 'calendar-file';
    stagingStatus.textContent = 'Reading the file…';
    stagingCard.hidden = false;
    stagingCard.scrollIntoView({ block: 'nearest' });

    const abortController = new AbortController();
    currentAbortController = abortController;

    try {
      const formData = new FormData();
      formData.append('photo', file);
      const res = await fetch('/inbox/import-photo/extract', {
        method: 'POST',
        body: formData,
        signal: abortController.signal
      });
      const data = await res.json();
      if (data.error) {
        showError(data.error);
      } else {
        data.events.forEach(addEventRow);
        updateChrome();
      }
    } catch (e) {
      // A user-initiated abort (stagingCancel below) throws the same way a
      // real network failure would - only show the error banner for the
      // latter, since the former is an intentional "never mind."
      if (e.name !== 'AbortError') {
        showError("Couldn't reach the server - check your connection and try again.");
      }
    } finally {
      resetStaging();
    }
  }

  cameraInput.addEventListener('change', () => onFileChosen(cameraInput.files && cameraInput.files[0]));
  libraryInput.addEventListener('change', () => onFileChosen(libraryInput.files && libraryInput.files[0]));

  stagingCancel.addEventListener('click', () => {
    if (currentAbortController) currentAbortController.abort();
    resetStaging();
  });

  updateChrome();
})();
