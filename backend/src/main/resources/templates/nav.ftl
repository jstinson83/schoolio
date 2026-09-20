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
        <a class="nav-link<#if (activeNav!"") == "inbox"> active</#if>" href="/inbox">Inbox</a>
        <a class="nav-link<#if (activeNav!"") == "updates"> active</#if>" href="/inbox/updates">Updates</a>
        <a class="nav-link<#if (activeNav!"") == "settings"> active</#if>" href="/inbox/settings">Settings</a>
        <a class="nav-link nav-link-subtle<#if (activeNav!"") == "dismissed"> active</#if>" href="/inbox/dismissed">Dismissed</a>
        <form method="post" action="/logout">
            <button type="submit" class="nav-signout">Sign out</button>
        </form>
    </div>
</header>
