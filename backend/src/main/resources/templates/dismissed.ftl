<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Dismissed - Schoolio</title>
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
        <h1>Dismissed</h1>

        <#if dateGroups?size == 0 && dismissedMessages?size == 0>
            <p>Nothing dismissed yet.</p>
        <#else>
            <#list dateGroups as group>
                <section class="date-group">
                    <h2 class="date-heading">${group.displayDate}</h2>
                    <ul class="action-items">
                        <#list group.items as action>
                            <li class="action-item action-item-dismissed">
                                <div class="action-item-main">
                                    <span class="action-title">${action.title}</span>
                                    <#if action.date?has_content><span class="action-due">${action.date}</span></#if>
                                </div>
                                <#if action.description?has_content><p class="action-description">${action.description}</p></#if>
                                <#if action.subject?has_content>
                                    <p class="action-source">From "${action.subject}"<#if action.from?has_content> &middot; ${action.from}</#if><#if action.summary?has_content> &mdash; ${action.summary}</#if><#if action.gmailLink?has_content> &middot; <a href="${action.gmailLink}" target="_blank" rel="noopener">Open in Gmail</a></#if></p>
                                <#elseif action.photoImport>
                                    <p class="action-source">From a file you uploaded</p>
                                <#else>
                                    <p class="action-source">From your calendar</p>
                                </#if>
                                <form method="post" action="/inbox/action-items/${action.id}/restore" class="dismiss-form">
                                    <button type="submit" class="btn-dismiss">Restore</button>
                                </form>
                            </li>
                        </#list>
                    </ul>
                </section>
            </#list>

            <#if dismissedMessages?size gt 0>
                <section class="other-updates">
                    <h2>Other updates</h2>
                    <ul class="message-list">
                        <#list dismissedMessages as message>
                            <li class="message">
                                <div class="message-subject">${message.subject}</div>
                                <#if message.summary?has_content><p class="message-summary">${message.summary}</p></#if>
                                <#if message.gmailLink?has_content><p class="message-summary"><a href="${message.gmailLink}" target="_blank" rel="noopener">Open in Gmail</a></p></#if>
                                <form method="post" action="/inbox/messages/${message.id}/restore" class="dismiss-form">
                                    <button type="submit" class="btn-dismiss">Restore</button>
                                </form>
                            </li>
                        </#list>
                    </ul>
                </section>
            </#if>
        </#if>
    </main>
    <script src="/app.js"></script>
</body>
</html>
