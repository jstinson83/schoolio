<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Settings - Schoolio</title>
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bitter:wght@600;700&family=Karla:wght@400;500;600;700&family=IBM+Plex+Mono:wght@500;600&display=swap">
    <link rel="stylesheet" href="/css/base.css">
</head>
<body>
    <#include "nav.ftl">
    <main class="settings">
        <h1>Settings</h1>

        <section class="settings-section">
            <h2>Gmail connection</h2>
            <form method="post" action="/inbox/connect-gmail" class="settings-form">
                <label>
                    Gmail app password
                    <input type="password" name="appPassword" placeholder="<#if hasAppPassword>already connected - enter a new one to replace it<#else>paste your app password here</#if>" autocomplete="off">
                </label>
                <p class="field-hint">Generate one at
                    <a href="https://myaccount.google.com/apppasswords" target="_blank" rel="noopener">myaccount.google.com/apppasswords</a>
                    (requires 2-Step Verification on your Google account).</p>
                <button type="submit" class="btn btn-primary">Save</button>
            </form>
        </section>

        <section class="settings-section">
            <h2>School senders</h2>
            <form method="post" action="/inbox/settings" class="settings-form">
                <label>
                    School senders (comma-separated addresses or domains)
                    <input type="text" name="senders" value="${sendersText}" placeholder="teacher@school.example, pta@school.example">
                </label>
                <label>
                    First-time lookback (weeks)
                    <input type="number" name="lookbackWeeks" min="1" max="52" value="${lookbackWeeks?c}">
                </label>
                <button type="submit" class="btn btn-primary">Save &amp; rescan</button>
            </form>
        </section>
    </main>
    <script src="/app.js"></script>
</body>
</html>
