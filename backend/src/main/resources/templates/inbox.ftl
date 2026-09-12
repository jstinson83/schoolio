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

        <form method="post" action="/inbox/settings" class="settings-form">
            <label>
                School senders (comma-separated addresses or domains)
                <input type="text" name="senders" value="${sendersText}" placeholder="teacher@school.example, pta@school.example">
            </label>
            <label>
                Lookback weeks
                <input type="number" name="lookbackWeeks" min="1" max="52" value="${lookbackWeeks?c}">
            </label>
            <button type="submit">Save &amp; rescan</button>
        </form>

        <#if needsGmailAccess??>
            <p>Gmail access hasn't been granted yet - <a href="/auth/google">sign in again</a> and accept
                the Gmail permission to see your recent messages here.</p>
        <#elseif noSendersConfigured??>
            <p>No school senders are configured yet - add at least one above.</p>
        <#elseif items??>
            <p class="inbox-scope">Scanning the last ${lookbackWeeks} week<#if lookbackWeeks != 1>s</#if>
                of email from configured senders.</p>
            <#if items?size == 0>
                <p>No messages found.</p>
            <#else>
                <ul class="message-list">
                    <#list items as item>
                        <li class="message">
                            <div class="message-subject">${item.subject}</div>
                            <div class="message-meta">${item.from} &middot; ${item.date}</div>
                            <#if item.summary?has_content>
                                <p class="message-summary">${item.summary}</p>
                            </#if>
                            <#if item.actionItems?size gt 0>
                                <ul class="action-items">
                                    <#list item.actionItems as action>
                                        <li>
                                            ${action.description}
                                            <#if action.dueDate?? || action.dueTime??>
                                                <span class="action-due">(<#if action.dueDate??>${action.dueDate}</#if><#if action.dueTime??> ${action.dueTime}</#if>)</span>
                                            </#if>
                                        </li>
                                    </#list>
                                </ul>
                            </#if>
                        </li>
                    </#list>
                </ul>
            </#if>
        </#if>
    </main>
</body>
</html>
