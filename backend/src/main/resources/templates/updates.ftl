<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Updates - Schoolio</title>
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
        <h1>Updates</h1>

        <#if noActionMessages?size gt 0>
            <section class="other-updates">
                <ul class="message-list">
                    <#list noActionMessages as message>
                        <li class="message">
                            <div class="message-subject">${message.subject}</div>
                            <#if message.summary?has_content><p class="message-summary">${message.summary}</p></#if>
                            <p class="message-summary"><a href="/inbox/messages/${message.id?url('UTF-8')}">View email</a></p>
                            <form method="post" action="/inbox/messages/${message.id?url('UTF-8')}/dismiss" class="dismiss-form">
                                <button type="submit" class="btn-dismiss">Dismiss</button>
                            </form>
                        </li>
                    </#list>
                </ul>
            </section>
        </#if>

        <#if failedMessages?size gt 0>
            <section class="failed-messages">
                <h2>Couldn't process</h2>
                <ul class="message-list">
                    <#list failedMessages as message>
                        <li class="message">
                            <div class="message-subject">${message.subject}</div>
                            <p class="message-failed">Couldn't process this message<#if message.reason?has_content>: ${message.reason}</#if></p>
                            <#if message.id?has_content><p class="message-summary"><a href="/inbox/messages/${message.id?url('UTF-8')}">View email</a></p></#if>
                        </li>
                    </#list>
                </ul>
            </section>
        </#if>

        <#if noActionMessages?size == 0 && failedMessages?size == 0>
            <p>Nothing here yet.</p>
        </#if>
    </main>
    <script src="/app.js"></script>
</body>
</html>
