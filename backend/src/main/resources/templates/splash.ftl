<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Schoolio</title>
    <link rel="icon" type="image/svg+xml" href="/logo.svg">
    <link rel="manifest" href="/manifest.json">
    <link rel="apple-touch-icon" href="/logo.svg">
    <meta name="theme-color" content="#1f3a5f">
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bitter:wght@600;700&family=Karla:wght@400;500;600;700&family=IBM+Plex+Mono:wght@500;600&display=swap">
    <link rel="stylesheet" href="/css/base.css">
</head>
<body class="splash-body">
    <main class="splash-page">
        <div class="splash-card">
            <img class="splash-icon" src="/logo.svg" alt="">
            <h1>Schoolio</h1>

            <#-- Application.kt always puts "authError" in this model, as a real
                 Boolean (true/false), never absent - so "authError??" (which tests
                 whether the variable is *defined*, not whether it's *true*) was
                 always true here regardless of the query param, showing this
                 banner on every plain signed-out visit. "authError!false" reads
                 its actual value (defaulting to false only if it were ever
                 missing). currentUser is never in this template's model either -
                 Application.kt's "/" handler redirects a signed-in visitor
                 straight to /inbox before splash.ftl ever renders (see its own
                 comment), so this only ever renders for a signed-out visitor.
                 That's also what makes a stray ?authError=1 on a successful
                 sign-in harmless: a duplicate /auth/google/callback hit reusing an
                 already-consumed authorization code can redirect here with
                 authError=1 even after an earlier request already completed
                 sign-in - but by the time the browser follows that redirect, the
                 session cookie is set, so "/" sends it straight to /inbox instead
                 of rendering this banner. See
                 AuthTest.kt's testSuccessfulSignInHidesErrorBannerEvenWithStaleAuthErrorParam. -->
            <#if authError!false>
                <p class="banner-error">Couldn't sign you in - either something went wrong, or that
                    Google account isn't allowed to use this app.</p>
            </#if>

            <p class="splash-subtitle">Turns school email into calendar-ready items.</p>
            <a class="google-button" href="/auth/google">
                <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true">
                    <path fill="#4285F4" d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.9c1.7-1.57 2.7-3.87 2.7-6.62z"/>
                    <path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.9-2.26c-.8.54-1.84.86-3.06.86-2.35 0-4.34-1.59-5.05-3.72H.96v2.33A9 9 0 0 0 9 18z"/>
                    <path fill="#FBBC05" d="M3.95 10.7A5.4 5.4 0 0 1 3.67 9c0-.59.1-1.17.28-1.7V4.97H.96A9 9 0 0 0 0 9c0 1.45.35 2.83.96 4.03l2.99-2.33z"/>
                    <path fill="#EA4335" d="M9 3.58c1.32 0 2.51.45 3.44 1.35l2.58-2.58C13.46.89 11.43 0 9 0A9 9 0 0 0 .96 4.97l2.99 2.33C4.66 5.17 6.65 3.58 9 3.58z"/>
                </svg>
                Sign in with Google
            </a>

            <#if revision??>
                <p class="revision">Cloud Run revision: <code>${revision}</code></p>
            <#else>
                <p class="revision">Running locally (no Cloud Run revision).</p>
            </#if>
        </div>
    </main>
</body>
</html>
