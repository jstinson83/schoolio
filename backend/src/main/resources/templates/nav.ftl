<header class="app-nav">
    <div class="app-nav-bar">
        <a class="app-nav-brand" href="/inbox">Schoolio</a>
        <nav class="app-nav-links">
            <a class="nav-link<#if (activeNav!"") == "inbox"> active</#if>" href="/inbox">Inbox</a>
            <a class="nav-link<#if (activeNav!"") == "updates"> active</#if>" href="/inbox/updates">Updates</a>
            <a class="nav-link<#if (activeNav!"") == "settings"> active</#if>" href="/inbox/settings">Settings</a>
            <a class="nav-link nav-link-subtle<#if (activeNav!"") == "dismissed"> active</#if>" href="/inbox/dismissed">Dismissed</a>
        </nav>
        <form method="post" action="/logout" class="app-nav-signout">
            <button type="submit" class="nav-signout">Sign out</button>
        </form>
        <button type="button" class="app-nav-toggle" id="navToggle" aria-label="Menu" aria-expanded="false" aria-controls="navMenu">
            <span></span><span></span><span></span>
        </button>
    </div>
    <div class="app-nav-menu" id="navMenu" hidden>
        <a class="nav-link<#if (activeNav!"") == "settings"> active</#if>" href="/inbox/settings">Settings</a>
        <a class="nav-link nav-link-subtle<#if (activeNav!"") == "dismissed"> active</#if>" href="/inbox/dismissed">Dismissed</a>
        <form method="post" action="/logout">
            <button type="submit" class="nav-signout">Sign out</button>
        </form>
    </div>
</header>
<#-- Mobile-only footer tab bar (hidden on wider viewports via base.css) -
     Inbox/Updates move here so they're reachable without opening the
     hamburger; Settings/Dismissed/Sign out stay in navMenu above. -->
<nav class="app-nav-footer" aria-label="Primary">
    <a class="app-nav-footer-link<#if (activeNav!"") == "inbox"> active</#if>" href="/inbox">
        <svg class="app-nav-footer-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 6h11"/><path d="M9 12h11"/><path d="M9 18h11"/><path d="M4 6h.01"/><path d="M4 12h.01"/><path d="M4 18h.01"/></svg>
        <span class="app-nav-footer-label">What's going on</span>
    </a>
    <a class="app-nav-footer-link<#if (activeNav!"") == "updates"> active</#if>" href="/inbox/updates">
        <svg class="app-nav-footer-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
        <span class="app-nav-footer-label">Updates</span>
    </a>
</nav>
