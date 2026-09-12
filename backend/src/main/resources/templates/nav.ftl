<header class="app-nav">
    <a class="app-nav-brand" href="/inbox">Schoolio</a>
    <nav class="app-nav-links">
        <a class="btn btn-ghost<#if (activeNav!"") == "inbox"> active</#if>" href="/inbox">Inbox</a>
        <a class="btn btn-ghost<#if (activeNav!"") == "settings"> active</#if>" href="/inbox/settings">Settings</a>
    </nav>
    <form method="post" action="/logout" class="app-nav-signout">
        <button type="submit" class="btn btn-secondary">Sign out</button>
    </form>
</header>
