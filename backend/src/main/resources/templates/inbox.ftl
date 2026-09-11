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

        <#if needsGmailAccess??>
            <p>Gmail access hasn't been granted yet - <a href="/auth/google">sign in again</a> and accept
                the Gmail permission to see your recent messages here.</p>
        <#elseif messages??>
            <#if messages?size == 0>
                <p>No messages found.</p>
            <#else>
                <ul class="message-list">
                    <#list messages as message>
                        <li class="message">
                            <div class="message-subject">${message.subject}</div>
                            <div class="message-meta">${message.from} &middot; ${message.date}</div>
                        </li>
                    </#list>
                </ul>
            </#if>
        </#if>
    </main>
</body>
</html>
