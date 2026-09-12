<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Schoolio</title>
    <link rel="stylesheet" href="/css/base.css">
</head>
<body>
    <main class="splash">
        <h1>Schoolio</h1>

        <#-- Deliberately gated on !currentUser??, not just authError?? - a stray
             authError=1 can land in the URL even after a successful sign-in (e.g.
             a duplicate /auth/google/callback hit reusing an already-consumed
             authorization code), and showing this banner on top of a real signed-in
             session is confusing/wrong, not just cosmetic. -->
        <#if authError?? && !(currentUser??)>
            <p class="banner-error">Couldn't sign you in - either something went wrong, or that Google
                account isn't allowed to use this app.</p>
        </#if>

        <#if currentUser??>
            <p>Signed in as ${currentUser.email}.</p>
            <p><a class="btn btn-primary" href="/inbox">View inbox</a></p>
            <form method="post" action="/logout">
                <button type="submit" class="btn btn-secondary">Sign out</button>
            </form>
        <#else>
            <p>The backend is up.</p>
            <p><a class="btn btn-primary" href="/auth/google">Sign in with Google</a></p>
        </#if>

        <#if revision??>
            <p class="revision">Cloud Run revision: <code>${revision}</code></p>
        <#else>
            <p class="revision">Running locally (no Cloud Run revision).</p>
        </#if>
    </main>
</body>
</html>
