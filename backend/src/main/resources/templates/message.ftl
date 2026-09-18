<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${message.subject} - Schoolio</title>
    <link rel="icon" type="image/svg+xml" href="/logo.svg">
    <link rel="manifest" href="/manifest.json">
    <link rel="apple-touch-icon" href="/logo.svg">
    <meta name="theme-color" content="#1f3a5f">
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bitter:wght@600;700&family=Karla:wght@400;500;600;700&family=IBM+Plex+Mono:wght@500;600&display=swap">
    <link rel="stylesheet" href="/css/base.css">
</head>
<body>
    <#include "nav.ftl">
    <main class="settings message-detail">
        <p><a href="/inbox">&larr; Back to inbox</a></p>
        <h1>${message.subject}</h1>
        <p class="message-meta"><#if message.from?has_content>${message.from}</#if><#if message.date?has_content> &middot; ${message.date}</#if></p>

        <#if message.summary?has_content>
            <section class="settings-section">
                <h2>Summary</h2>
                <p>${message.summary}</p>
            </section>
        </#if>

        <section class="settings-section">
            <h2>Message</h2>
            <div class="message-body">${message.bodyText}</div>
        </section>

        <#if message.attachments?size gt 0>
            <section class="settings-section">
                <h2>Attachments</h2>
                <ul class="attachment-list">
                    <#list message.attachments as attachment>
                        <li><a href="/inbox/messages/${message.id?url('UTF-8')}/attachments/${attachment.index}">${attachment.filename}</a></li>
                    </#list>
                </ul>
            </section>
        </#if>
    </main>
</body>
</html>
