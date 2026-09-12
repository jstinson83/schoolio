<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Inbox - Schoolio</title>
    <link rel="stylesheet" href="/css/base.css">
</head>
<body>
    <main class="inbox">
        <h1>Inbox</h1>
        <p><a href="/">Back</a></p>

        <form method="post" action="/inbox/connect-gmail" class="settings-form">
            <label>
                Gmail app password
                <input type="password" name="appPassword" placeholder="<#if hasAppPassword>already connected - enter a new one to replace it<#else>paste your app password here</#if>" autocomplete="off">
            </label>
            <p class="field-hint">Generate one at
                <a href="https://myaccount.google.com/apppasswords" target="_blank" rel="noopener">myaccount.google.com/apppasswords</a>
                (requires 2-Step Verification on your Google account).</p>
            <button type="submit">Save</button>
        </form>

        <form method="post" action="/inbox/settings" class="settings-form">
            <label>
                School senders (comma-separated addresses or domains)
                <input type="text" name="senders" value="${sendersText}" placeholder="teacher@school.example, pta@school.example">
            </label>
            <label>
                First-time lookback (weeks)
                <input type="number" name="lookbackWeeks" min="1" max="52" value="${lookbackWeeks?c}">
            </label>
            <button type="submit">Save &amp; rescan</button>
        </form>

        <#if needsGmailAccess??>
            <p>Gmail isn't connected yet - enter an app password above to see your recent messages here.</p>
        <#elseif noSendersConfigured??>
            <p>No school senders are configured yet - add at least one above.</p>
        <#elseif items??>
            <p class="inbox-scope">Scanning for new email from configured senders since the last scan
                (or the last ${lookbackWeeks} week<#if lookbackWeeks != 1>s</#if> for a sender scanned for the first time).</p>
            <#if pendingCount gt 0>
                <div id="processingBanner" class="banner banner-processing">
                    <span class="processing-banner-text">Processing <#if pendingCount == 1>1 message<#else>${pendingCount} messages</#if>&hellip;</span>
                </div>
            </#if>
            <#if items?size == 0>
                <p>No messages found.</p>
            <#else>
                <ul class="message-list" id="messageList">
                    <#list items as item>
                        <li class="message" data-id="${item.id}" data-status="${item.status}">
                            <div class="message-subject">${item.subject}</div>
                            <div class="message-meta">${item.from} &middot; ${item.date}</div>
                            <div class="message-body">
                                <#if item.status == "PENDING">
                                    <p class="message-pending">Processing&hellip;</p>
                                <#elseif item.status == "FAILED">
                                    <p class="message-failed">Couldn't process this message<#if item.failureReason?has_content>: ${item.failureReason}</#if></p>
                                <#else>
                                    <#if item.summary?has_content>
                                        <p class="message-summary">${item.summary}</p>
                                    </#if>
                                    <#if item.actionItems?size gt 0>
                                        <ul class="action-items">
                                            <#list item.actionItems as action>
                                                <li>
                                                    <span class="action-title">${action.title}</span>
                                                    <#if action.description?has_content> &mdash; ${action.description}</#if>
                                                    <#if action.date?has_content>
                                                        <span class="action-due">(${action.date})</span>
                                                    </#if>
                                                </li>
                                            </#list>
                                        </ul>
                                    </#if>
                                </#if>
                            </div>
                        </li>
                    </#list>
                </ul>
            </#if>
        </#if>
    </main>
    <script src="/app.js"></script>
</body>
</html>
