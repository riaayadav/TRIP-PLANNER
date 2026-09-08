<%@ page contentType="text/html;charset=UTF-8" session="false" %>
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <meta name="description" content="Discover destinations, plan your itinerary and keep every part of your trip in one place.">
    <title>Roam · Trip Planner</title>
    <link rel="stylesheet" href="assets/bootstrap.min.css">
    <link rel="stylesheet" href="assets/app.css?v=readability-5">
    <script defer src="assets/packages.js?v=readability-5"></script>
    <script defer src="assets/app.js?v=readability-5"></script>
  </head>
  <body>
    <a class="skip-link" href="#main">Skip to content</a>
    <div id="app">
      <main class="loading" aria-live="polite">Opening your travel workspace…</main>
    </div>
    <div id="toast" role="status" aria-live="polite"></div>
    <dialog id="modal" aria-labelledby="dialog-title"></dialog>
  </body>
</html>
