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
    <main class="inbox">
        <h1>Import from a photo</h1>
        <p>Take (or choose) a photo of a calendar - a paper wall calendar, a printed
            school schedule, or a whiteboard - and Schoolio will read off the events
            for you to review before adding them.</p>

        <#if error??>
            <div class="banner banner-error">${error}</div>
        </#if>

        <form method="post" action="/inbox/import-photo" enctype="multipart/form-data" class="settings-form">
            <label>
                Calendar photo
                <input type="file" name="photo" accept="image/*" capture="environment" required>
            </label>
            <button type="submit" class="btn btn-primary">Extract events</button>
        </form>
    </main>
    <script src="/app.js"></script>
</body>
</html>
