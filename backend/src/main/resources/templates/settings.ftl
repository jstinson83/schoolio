<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Settings - Schoolio</title>
    <link rel="icon" type="image/svg+xml" href="/logo.svg">
    <link rel="manifest" href="/manifest.json">
    <link rel="apple-touch-icon" href="/logo.svg">
    <meta name="theme-color" content="#1f3a5f">
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
            <h2>Calendar connection</h2>
            <#if calendarServiceAccountEmail?has_content>
                <p>Share your Google Calendar with
                    <strong>${calendarServiceAccountEmail}</strong>
                    (Calendar &rarr; Settings and sharing &rarr; Share with specific people &rarr; add that address with
                    "See all event details") to see your upcoming events here - no password to enter, and nothing to
                    submit on this page. It can take a few minutes after sharing before events show up on
                    <a href="/inbox">the inbox</a>.</p>
            <#else>
                <p>Calendar access isn't configured on this deployment yet.</p>
            </#if>
        </section>

        <section class="settings-section">
            <h2>Notifications</h2>
            <#if vapidPublicKey?has_content>
                <p id="notificationsUnsupported" class="field-hint" hidden>Push notifications aren't supported in this browser.
                    On iOS, add Schoolio to your home screen first (Share &rarr; Add to Home Screen), then open it from
                    there.</p>
                <p>Get a once-daily notification in the morning, only on days something's due.</p>
                <#-- data-subscribed starts false regardless of server state - "subscribed" now means
                     "this device", which the server can't know at render time since an account's
                     subscriptions live per-device (see UserStore.kt's pushSubscriptions). app.js
                     corrects this against the browser's own PushManager.getSubscription() on load. -->
                <button type="button" id="notifyToggle" class="btn btn-primary" data-vapid-key="${vapidPublicKey}"
                    data-subscribed="false">
                    Enable notifications
                </button>
                <p id="notifyError" class="field-hint" hidden></p>
            <#else>
                <p>Push notifications aren't configured on this deployment yet.</p>
            </#if>
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
