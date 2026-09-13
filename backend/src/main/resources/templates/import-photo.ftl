<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Import from a photo - Schoolio</title>
    <link rel="icon" type="image/svg+xml" href="/logo.svg">
    <link rel="manifest" href="/manifest.json">
    <link rel="apple-touch-icon" href="/logo.svg">
    <meta name="theme-color" content="#1f3a5f">
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bitter:wght@600;700&family=Karla:wght@400;500;600;700&family=IBM+Plex+Mono:wght@500;600&display=swap">
    <link rel="stylesheet" href="/css/base.css">
</head>
<body>
    <#include "nav.ftl">
    <main class="inbox inbox-photo-import">
        <h1>Import from a photo</h1>
        <p>Tap the <strong>+</strong> in the corner to take or choose a photo of a
            calendar - a paper wall calendar, a printed school schedule, or a
            whiteboard. Add as many photos as you like; everything Schoolio reads
            off lands in the list below for you to review before it's added.</p>

        <div id="importError" class="banner banner-error" hidden></div>

        <div id="stagingCard" class="staging-card" hidden>
            <div class="staging-thumb" aria-hidden="true">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M4 8a2 2 0 0 1 2-2h1.2l.9-1.5A1.5 1.5 0 0 1 9.4 4h5.2a1.5 1.5 0 0 1 1.3.75L16.8 6H18a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8Z" stroke="currentColor" stroke-width="1.6"/><circle cx="12" cy="13" r="3.2" stroke="currentColor" stroke-width="1.6"/></svg>
            </div>
            <div class="staging-info">
                <div id="stagingName" class="staging-name"></div>
                <div id="stagingStatus" class="staging-status">Ready to extract</div>
            </div>
            <div class="staging-actions">
                <button type="button" id="stagingCancel" class="btn btn-ghost" aria-label="Remove photo">&#x2715;</button>
                <button type="button" id="extractBtn" class="btn btn-primary">Extract events</button>
            </div>
        </div>

        <div id="emptyState" class="empty-state">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none"><path d="M4 8a2 2 0 0 1 2-2h1.2l.9-1.5A1.5 1.5 0 0 1 9.4 4h5.2a1.5 1.5 0 0 1 1.3.75L16.8 6H18a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8Z" stroke="currentColor" stroke-width="1.5"/><circle cx="12" cy="13" r="3.2" stroke="currentColor" stroke-width="1.5"/></svg>
            <span>No events yet - tap the + to add your first photo.</span>
        </div>

        <form id="confirmForm" method="post" action="/inbox/import-photo/confirm">
            <input type="hidden" name="count" id="eventCount" value="0">
            <ul id="eventsList" class="action-items"></ul>

            <div id="confirmBar" class="confirm-bar" hidden>
                <button type="submit" class="btn btn-primary">Add selected events</button>
            </div>
        </form>
    </main>

    <div id="fabContainer" class="fab-container">
        <div id="fabMenu" class="fab-menu" hidden>
            <button type="button" id="cameraOption" class="fab-option">
                <span class="fab-option-icon" aria-hidden="true">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M4 8a2 2 0 0 1 2-2h1.2l.9-1.5A1.5 1.5 0 0 1 9.4 4h5.2a1.5 1.5 0 0 1 1.3.75L16.8 6H18a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8Z" stroke="currentColor" stroke-width="1.7"/><circle cx="12" cy="13" r="3.2" stroke="currentColor" stroke-width="1.7"/></svg>
                </span>
                Take a photo
            </button>
            <button type="button" id="libraryOption" class="fab-option">
                <span class="fab-option-icon" aria-hidden="true">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><rect x="3" y="5" width="18" height="14" rx="2" stroke="currentColor" stroke-width="1.7"/><circle cx="8.5" cy="10" r="1.5" stroke="currentColor" stroke-width="1.7"/><path d="M21 16.5 15.6 11a1 1 0 0 0-1.4 0L7 18.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
                </span>
                Choose a file
            </button>
        </div>
        <button type="button" id="fabButton" class="fab" aria-haspopup="true" aria-expanded="false" aria-label="Add a photo of a calendar">
            <svg id="fabIcon" width="26" height="26" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/>
            </svg>
        </button>
    </div>
    <input type="file" id="cameraInput" name="photo" accept="image/*" capture="environment" hidden>
    <input type="file" id="libraryInput" name="photo" accept="image/*" hidden>

    <script src="/app.js"></script>
</body>
</html>
