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
        <p>Tap the camera icon in the corner to take (or choose) a photo of a
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
            <span>No events yet - tap the camera icon to add your first photo.</span>
        </div>

        <form id="confirmForm" method="post" action="/inbox/import-photo/confirm">
            <input type="hidden" name="count" id="eventCount" value="0">
            <ul id="eventsList" class="action-items"></ul>

            <div id="confirmBar" class="confirm-bar" hidden>
                <button type="submit" class="btn btn-primary">Add selected events</button>
            </div>
        </form>
    </main>

    <button type="button" id="fabButton" class="fab" aria-label="Add a photo of a calendar">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M4 8a2 2 0 0 1 2-2h1.2l.9-1.5A1.5 1.5 0 0 1 9.4 4h5.2a1.5 1.5 0 0 1 1.3.75L16.8 6H18a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8Z" stroke="currentColor" stroke-width="1.8"/>
            <circle cx="12" cy="13" r="3.4" stroke="currentColor" stroke-width="1.8"/>
        </svg>
    </button>
    <input type="file" id="photoInput" name="photo" accept="image/*" capture="environment" hidden>

    <script src="/app.js"></script>
</body>
</html>
