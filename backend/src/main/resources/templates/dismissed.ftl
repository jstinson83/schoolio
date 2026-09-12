<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Dismissed - Schoolio</title>
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bitter:wght@600;700&family=Karla:wght@400;500;600;700&family=IBM+Plex+Mono:wght@500;600&display=swap">
    <link rel="stylesheet" href="/css/base.css">
</head>
<body>
    <#include "nav.ftl">
    <main class="inbox">
        <h1>Dismissed</h1>

        <#if dateGroups?size == 0>
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
                                <p class="action-source">From "${action.subject}"<#if action.from?has_content> &middot; ${action.from}</#if><#if action.summary?has_content> &mdash; ${action.summary}</#if></p>
                                <form method="post" action="/inbox/action-items/${action.id}/restore" class="dismiss-form">
                                    <button type="submit" class="btn-dismiss">Restore</button>
                                </form>
                            </li>
                        </#list>
                    </ul>
                </section>
            </#list>
        </#if>
    </main>
    <script src="/app.js"></script>
</body>
</html>
