<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Inbox - Schoolio</title>
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bitter:wght@600;700&family=Karla:wght@400;500;600;700&family=IBM+Plex+Mono:wght@500;600&display=swap">
    <link rel="stylesheet" href="/css/base.css">
</head>
<body>
    <#include "nav.ftl">
    <main class="inbox">
        <h1>Action items</h1>

        <#if needsGmailAccess??>
            <p>Gmail isn't connected yet - <a href="/inbox/settings">connect it in Settings</a> to see your recent messages here.</p>
        <#elseif noSendersConfigured??>
            <p>No school senders are configured yet - <a href="/inbox/settings">add at least one in Settings</a>.</p>
        <#else>
            <#if pendingCount gt 0>
                <div id="processingBanner" class="banner banner-processing">
                    <span class="processing-banner-text">Processing <#if pendingCount == 1>1 message<#else>${pendingCount} messages</#if>&hellip;</span>
                    <#if pendingMessages?size gt 0>
                        <ul class="pending-list">
                            <#list pendingMessages as pending>
                                <li>${pending.subject}</li>
                            </#list>
                        </ul>
                    </#if>
                </div>
            </#if>

            <#list dateGroups as group>
                <section class="date-group">
                    <h2 class="date-heading">${group.displayDate}</h2>
                    <ul class="action-items">
                        <#list group.items as action>
                            <li class="action-item">
                                <div class="action-item-main">
                                    <span class="action-title">${action.title}</span>
                                    <#if action.date?has_content><span class="action-due">${action.date}</span></#if>
                                </div>
                                <#if action.description?has_content><p class="action-description">${action.description}</p></#if>
                                <p class="action-source">From "${action.subject}"<#if action.from?has_content> &middot; ${action.from}</#if><#if action.summary?has_content> &mdash; ${action.summary}</#if></p>
                            </li>
                        </#list>
                    </ul>
                </section>
            </#list>

            <#if noActionMessages?size gt 0>
                <section class="other-updates">
                    <h2>Other updates</h2>
                    <ul class="message-list">
                        <#list noActionMessages as message>
                            <li class="message">
                                <div class="message-subject">${message.subject}</div>
                                <#if message.summary?has_content><p class="message-summary">${message.summary}</p></#if>
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
                            </li>
                        </#list>
                    </ul>
                </section>
            </#if>

            <#if dateGroups?size == 0 && noActionMessages?size == 0 && failedMessages?size == 0 && pendingCount == 0>
                <p>No messages found.</p>
            </#if>
        </#if>
    </main>
    <script src="/app.js"></script>
</body>
</html>
