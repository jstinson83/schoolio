// One shared file, per-page blocks gated on a DOM element that only exists
// on that page - same convention as foodie's app.js.

// Inbox page (inbox.ftl) - poll while any message is still PENDING,
// reconciling each message's <li> in place instead of reloading the whole
// page. Every message already has its own <li data-id> from the initial
// render (see InboxRoutes.kt/inbox.ftl) - unlike foodie's recipe-status
// poller, nothing here needs inserting, only updating in place once a
// message finishes processing.
(function () {
  const processingBanner = document.getElementById('processingBanner');
  const messagesList = document.getElementById('messageList');
  if (!processingBanner || !messagesList) return;

  function buildActionItemsList(actionItems) {
    if (!actionItems.length) return '';
    const items = actionItems.map((action) => {
      const title = document.createElement('span');
      title.className = 'action-title';
      title.textContent = action.title;
      const li = document.createElement('li');
      li.appendChild(title);
      if (action.description) li.append(' — ' + action.description);
      if (action.date) {
        const due = document.createElement('span');
        due.className = 'action-due';
        due.textContent = ' (' + action.date + ')';
        li.appendChild(due);
      }
      return li;
    });
    const list = document.createElement('ul');
    list.className = 'action-items';
    items.forEach((li) => list.appendChild(li));
    return list;
  }

  function renderProcessed(li, message) {
    li.dataset.status = 'PROCESSED';
    const body = li.querySelector('.message-body');
    body.innerHTML = '';
    if (message.summary) {
      const summary = document.createElement('p');
      summary.className = 'message-summary';
      summary.textContent = message.summary;
      body.appendChild(summary);
    }
    const actionItemsEl = buildActionItemsList(message.actionItems || []);
    if (actionItemsEl) body.appendChild(actionItemsEl);
  }

  function renderFailed(li, failed) {
    li.dataset.status = 'FAILED';
    const body = li.querySelector('.message-body');
    body.innerHTML = '';
    const p = document.createElement('p');
    p.className = 'message-failed';
    p.textContent = "Couldn't process this message" + (failed.reason ? ': ' + failed.reason : '');
    body.appendChild(p);
  }

  function reconcileInboxStatus(data) {
    for (const message of data.messages) {
      const li = messagesList.querySelector(`[data-id="${message.id}"]`);
      if (li && li.dataset.status !== 'PROCESSED') renderProcessed(li, message);
    }
    for (const failed of data.failed) {
      const li = messagesList.querySelector(`[data-id="${failed.id}"]`);
      if (li && li.dataset.status !== 'FAILED') renderFailed(li, failed);
    }
    if (data.pending === 0) {
      processingBanner.remove();
    } else {
      const label = data.pending === 1 ? 'Processing 1 message…' : `Processing ${data.pending} messages…`;
      processingBanner.querySelector('.processing-banner-text').textContent = label;
    }
  }

  const inboxPoll = setInterval(async () => {
    try {
      const res = await fetch('/inbox/status');
      const data = await res.json();
      reconcileInboxStatus(data);
      if (data.pending === 0) clearInterval(inboxPoll);
    } catch (e) {
      // Best effort - a failed poll just tries again next tick.
    }
  }, 3000);
})();
