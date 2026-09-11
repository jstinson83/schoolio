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
        <p>The backend is up.</p>
        <#if revision??>
            <p class="revision">Cloud Run revision: <code>${revision}</code></p>
        <#else>
            <p class="revision">Running locally (no Cloud Run revision).</p>
        </#if>
    </main>
</body>
</html>
