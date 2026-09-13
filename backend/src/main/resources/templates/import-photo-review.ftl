<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Review imported events - Schoolio</title>
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
        <h1>Review imported events</h1>
        <p>Here's what Schoolio read off your photo. Uncheck anything that's wrong or
            not worth tracking, fix up any details that got misread, then add the rest.</p>

        <form method="post" action="/inbox/import-photo/confirm">
            <input type="hidden" name="count" value="${count?c}">
            <ul class="action-items">
                <#list events as event>
                    <li class="action-item photo-review-item">
                        <label class="photo-review-include">
                            <input type="checkbox" name="include_${event.index}" checked>
                            Add this event
                        </label>
                        <label>
                            Title
                            <input type="text" name="title_${event.index}" value="${event.title}" required>
                        </label>
                        <div class="photo-review-date-time">
                            <label>
                                Date
                                <input type="date" name="date_${event.index}" value="${event.date}">
                            </label>
                            <label>
                                Time (optional)
                                <input type="time" name="time_${event.index}" value="${event.time}">
                            </label>
                        </div>
                        <label>
                            Notes (optional)
                            <input type="text" name="description_${event.index}" value="${event.description}">
                        </label>
                    </li>
                </#list>
            </ul>
            <button type="submit" class="btn btn-primary">Add selected events</button>
        </form>
    </main>
    <script src="/app.js"></script>
</body>
</html>
