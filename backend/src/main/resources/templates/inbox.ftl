<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Inbox - Schoolio</title>
    <link rel="icon" type="image/svg+xml" href="/logo.svg">
    <link rel="manifest" href="/manifest.json">
    <link rel="apple-touch-icon" href="/logo.svg">
    <meta name="theme-color" content="#1f3a5f">
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bitter:wght@600;700&family=Karla:wght@400;500;600;700&family=IBM+Plex+Mono:wght@500;600&display=swap">
    <link rel="stylesheet" href="/css/base.css">
</head>
<body>
    <#include "nav.ftl">
    <main class="inbox inbox-photo-import">
        <h1>Action items</h1>

        <div id="importError" class="banner banner-error" hidden></div>

        <div id="stagingCard" class="staging-card" hidden>
            <div class="staging-thumb" aria-hidden="true">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M4 8a2 2 0 0 1 2-2h1.2l.9-1.5A1.5 1.5 0 0 1 9.4 4h5.2a1.5 1.5 0 0 1 1.3.75L16.8 6H18a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8Z" stroke="currentColor" stroke-width="1.6"/><circle cx="12" cy="13" r="3.2" stroke="currentColor" stroke-width="1.6"/></svg>
            </div>
            <div class="staging-info">
                <div id="stagingName" class="staging-name"></div>
                <div id="stagingStatus" class="staging-status">Reading the file&hellip;</div>
            </div>
            <div class="staging-actions">
                <button type="button" id="stagingCancel" class="btn btn-ghost" aria-label="Cancel">&#x2715;</button>
            </div>
        </div>

        <form id="confirmForm" method="post" action="/inbox/import-photo/confirm">
            <input type="hidden" name="count" id="eventCount" value="0">
            <section id="photoReviewSection" class="photo-review-section" hidden>
                <h2>From your upload <span class="photo-review-tag">not yet added</span></h2>
                <ul id="eventsList" class="action-items"></ul>
                <div id="confirmBar" class="confirm-bar" hidden>
                    <button type="submit" class="btn btn-primary">Add selected events</button>
                </div>
            </section>
        </form>

        <#if needsGmailAccess??>
            <p>Gmail isn't connected yet - <a href="/inbox/settings">connect it in Settings</a> to see your recent messages here.</p>
        <#elseif noSendersConfigured??>
            <p>No school senders are configured yet - <a href="/inbox/settings">add at least one in Settings</a>.</p>
        <#else>
            <#if syncing || pendingCount gt 0>
                <div id="processingBanner" class="banner banner-processing">
                    <span class="processing-banner-text">
                        <#if syncing>
                            Checking your inbox for new mail&hellip;
                        <#else>
                            Processing <#if pendingCount == 1>1 message<#else>${pendingCount} messages</#if>&hellip;
                        </#if>
                    </span>
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
                                <#if action.subject?has_content>
                                    <p class="action-source">From "${action.subject}"<#if action.from?has_content> &middot; ${action.from}</#if><#if action.summary?has_content> &mdash; ${action.summary}</#if></p>
                                <#elseif action.photoImport>
                                    <p class="action-source">From a file you uploaded</p>
                                <#else>
                                    <p class="action-source">From your calendar</p>
                                </#if>
                                <form method="post" action="/inbox/action-items/${action.id}/dismiss" class="dismiss-form">
                                    <button type="submit" class="btn-dismiss">Dismiss</button>
                                </form>
                            </li>
                        </#list>
                    </ul>
                </section>
            </#list>

            <#if pastActionItems?size gt 0>
                <section class="past-events">
                    <h2>Past events</h2>
                    <ul class="action-items">
                        <#list pastActionItems as action>
                            <li class="action-item action-item-past">
                                <div class="action-item-main">
                                    <span class="action-title">${action.title}</span>
                                    <#if action.date?has_content><span class="action-due">${action.date}</span></#if>
                                </div>
                                <#if action.description?has_content><p class="action-description">${action.description}</p></#if>
                                <#if action.subject?has_content>
                                    <p class="action-source">From "${action.subject}"<#if action.from?has_content> &middot; ${action.from}</#if><#if action.summary?has_content> &mdash; ${action.summary}</#if></p>
                                <#elseif action.photoImport>
                                    <p class="action-source">From a file you uploaded</p>
                                <#else>
                                    <p class="action-source">From your calendar</p>
                                </#if>
                                <form method="post" action="/inbox/action-items/${action.id}/dismiss" class="dismiss-form">
                                    <button type="submit" class="btn-dismiss">Dismiss</button>
                                </form>
                            </li>
                        </#list>
                    </ul>
                </section>
            </#if>

            <#if noActionMessages?size gt 0>
                <section class="other-updates">
                    <h2>Other updates</h2>
                    <ul class="message-list">
                        <#list noActionMessages as message>
                            <li class="message">
                                <div class="message-subject">${message.subject}</div>
                                <#if message.summary?has_content><p class="message-summary">${message.summary}</p></#if>
                                <form method="post" action="/inbox/messages/${message.id}/dismiss" class="dismiss-form">
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
                            </li>
                        </#list>
                    </ul>
                </section>
            </#if>

            <#if !syncing && dateGroups?size == 0 && pastActionItems?size == 0 && noActionMessages?size == 0 && failedMessages?size == 0 && pendingCount == 0>
                <p>No messages found.</p>
            </#if>
        </#if>
    </main>

    <div id="fabContainer" class="fab-container">
        <div id="fabMenu" class="fab-menu" hidden>
            <button type="button" id="cameraOption" class="fab-option">
                <span class="fab-option-icon" aria-hidden="true">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><path d="M4 8a2 2 0 0 1 2-2h1.2l.9-1.5A1.5 1.5 0 0 1 9.4 4h5.2a1.5 1.5 0 0 1 1.3.75L16.8 6H18a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8Z" stroke="currentColor" stroke-width="1.7"/><circle cx="12" cy="13" r="3.2" stroke="currentColor" stroke-width="1.7"/></svg>
                </span>
                Take a photo
            </button>
            <button type="button" id="libraryOption" class="fab-option">
                <span class="fab-option-icon" aria-hidden="true">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><rect x="3" y="5" width="18" height="14" rx="2" stroke="currentColor" stroke-width="1.7"/><circle cx="8.5" cy="10" r="1.5" stroke="currentColor" stroke-width="1.7"/><path d="M21 16.5 15.6 11a1 1 0 0 0-1.4 0L7 18.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
                </span>
                Choose a file
            </button>
        </div>
        <button type="button" id="fabButton" class="fab" aria-haspopup="true" aria-expanded="false" aria-label="Import events from a photo or file">
            <svg id="fabIcon" width="26" height="26" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/>
            </svg>
        </button>
    </div>
    <input type="file" id="cameraInput" name="photo" accept="image/*" capture="environment" hidden>
    <input type="file" id="libraryInput" name="photo" accept="image/*,application/pdf,.pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,.docx" hidden>

    <script src="/app.js"></script>
</body>
</html>
