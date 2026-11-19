'use strict';

/**
 * =============================================================================
 * ROAM TRIP PLANNER - FRONTEND SPA APPLICATION (app.js)
 * =============================================================================
 * 
 * Architecture:
 * - Single Page Application (SPA) driven by hash-based routing (#home, #explore, etc.).
 * - Communicates with Jakarta Servlet backend via JSON REST API (/api/*).
 * - Implements CSRF protection with X-CSRF-Token headers on all state-changing requests.
 * - Supports responsive design, modal dialogs, and interactive dashboards.
 */

// =============================================================================
// 1. APPLICATION STATE & DOM REFERENCES
// =============================================================================

// Root DOM containers
const app = document.querySelector('#app'),
  modal = document.querySelector('#modal');

// Central application state
const state = {
  user: null,          // Authenticated user object (id, name, email, role)
  csrf: '',            // Cross-Site Request Forgery security token
  data: null,          // Main dashboard payload (destinations, trips, wishlist, memories)
  trip: null,          // Active detailed trip (with itinerary, budget, packing)
  admin: null,         // Admin dashboard payload (users, feedback, audit log)
  compare: new Set(),  // Set of destination IDs selected for comparison
  authMode: 'login',   // Current auth screen mode ('login' or 'register')
};

// =============================================================================
// 1.1 LOCAL STORAGE CACHE ENGINE (Stale-While-Revalidate & Instant Render)
// =============================================================================

const CACHE_TTL = {
  SESSION: 15 * 60 * 1000,   // 15 minutes
  DATA: 10 * 60 * 1000,      // 10 minutes
  PACKAGES: 15 * 60 * 1000,  // 15 minutes
  TRIP: 10 * 60 * 1000,      // 10 minutes
  ADMIN: 5 * 60 * 1000,      // 5 minutes
};

const roamCache = {
  get(key, maxAge = null) {
    try {
      const raw = localStorage.getItem(key);
      if (!raw) return null;
      const entry = JSON.parse(raw);
      if (!entry || typeof entry !== 'object' || !('v' in entry)) return null;
      const age = Date.now() - (entry.t || 0);
      const isStale = maxAge !== null && age > maxAge;
      return { data: entry.v, isStale, age };
    } catch (e) {
      return null;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify({ v: value, t: Date.now() }));
    } catch (e) {
      try {
        // Safe eviction of old cached trips if localStorage quota is exceeded
        const toRemove = [];
        for (let i = 0; i < localStorage.length; i++) {
          const k = localStorage.key(i);
          if (k && k.startsWith('roam_trip_')) toRemove.push(k);
        }
        toRemove.forEach((k) => localStorage.removeItem(k));
        localStorage.setItem(key, JSON.stringify({ v: value, t: Date.now() }));
      } catch (err) {}
    }
  },
  remove(key) {
    try {
      localStorage.removeItem(key);
    } catch (e) {}
  },
  clearUserData() {
    try {
      const keysToRemove = [];
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (k && k.startsWith('roam_')) keysToRemove.push(k);
      }
      keysToRemove.forEach((k) => localStorage.removeItem(k));
    } catch (e) {}
  },
};
window.roamCache = roamCache;

function saveCompareState() {
  try {
    roamCache.set('roam_compare_cache', [...state.compare]);
  } catch (e) {}
}

function loadCompareState() {
  try {
    const cached = roamCache.get('roam_compare_cache');
    if (cached && Array.isArray(cached.data)) {
      state.compare = new Set(cached.data.map(Number));
    }
  } catch (e) {}
}

// =============================================================================
// 2. DATA FORMATTING & STRING UTILITIES
// =============================================================================

// Formats numeric values to Indian Rupee (INR) currency: e.g. ₹2,800.00
const formatMoney = (v) =>
  new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 2,
  }).format(Number(v || 0));

// Sanitizes text to prevent Cross-Site Scripting (XSS) in HTML templates
const escapeHtml = (v) =>
  String(v ?? '').replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c],
  );
const formatDate = (d) =>
  new Date(d + 'T12:00:00').toLocaleDateString('en-IN', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
const localDate = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};
const tripDurationDays = (t) =>
  Math.round((Date.parse(t.end_date) - Date.parse(t.start_date)) / 86400000) + 1;
const findDestination = (id) => state.data?.destinations?.find((d) => Number(d.id) === Number(id));
const currentRoute = () => location.hash.slice(1) || 'home';
const icon = ['⌂', '☀', '◎', '✧', '＋', '₹', '▤', '✓', '⇄', '♡', '▧', '◷', '☆', '○', '↪'];
const nav = [
  ['home', 'Home / Dashboard'],
  ['season', 'Explore by season'],
  ['explore', 'Explore destinations'],
  ['perfect', 'Find my perfect trip'],
  ['plan', 'Plan my trip'],
  ['budget', 'Budget planner'],
  ['itinerary', 'My itinerary'],
  ['packing', 'Packing checklist'],
  ['compare', 'Compare destinations'],
  ['wishlist', 'Wishlist'],
  ['memories', 'Travel memories'],
  ['countdown', 'Trip countdown'],
  ['feedback', 'Feedback'],
  ['profile', 'My profile'],
  ['logout', 'Logout'],
];
let toastTimer,
  clockTimer,
  renderGeneration = 0;
// =============================================================================
// 3. NOTIFICATION & NETWORK API CLIENT (REST API)
// =============================================================================

/**
 * Displays a temporary toast notification message on screen.
 */
function toast(message, error = false) {
  const t = document.querySelector('#toast');
  t.textContent = message;
  t.className = 'visible' + (error ? ' error' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.className = ''), 5000);
}

/**
 * Central API fetch wrapper:
 * - Sends GET or POST requests to the backend /api/* endpoints
 * - Automatically injects X-CSRF-Token headers for POST requests
 * - Handles JSON serialization and parsing
 * - Automatically redirects to login if 401 Unauthorized is returned
 */
async function api(path, body) {
  let r;
  try {
    r = await fetch('api/' + path, {
      method: body ? 'POST' : 'GET',
      credentials: 'same-origin',
      headers: body ? { 'Content-Type': 'application/json', 'X-CSRF-Token': state.csrf } : {},
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch (e) {
    throw Error('Cannot reach the server. Check your connection and try again.');
  }
  let data;
  try {
    data = await r.json();
  } catch (e) {
    throw Error('The server returned an unexpected response. Please try again.');
  }
  if (!r.ok) {
    if (r.status === 401 && !['login', 'register'].includes(path)) {
      state.user = null;
      state.data = null;
      state.trip = null;
      state.admin = null;
      state.packages = null;
      state.compare.clear();
      state.csrf = '';
      roamCache.clearUserData();
      try {
        const fresh = await fetch('api/session', { credentials: 'same-origin' });
        if (fresh.ok) state.csrf = (await fresh.json()).csrf;
      } catch {}
      auth();
    }
    throw Error(data.error || 'Something went wrong.');
  }
  return data;
}

/**
 * Refreshes main dashboard data from /api/data and updates LocalStorage cache.
 */
async function refresh(isBackground = false) {
  const fresh = await api('data');
  const hasChanged = JSON.stringify(state.data) !== JSON.stringify(fresh);
  state.data = fresh;
  roamCache.set('roam_data_cache', fresh);
  return hasChanged;
}

// =============================================================================
// 4. REUSABLE UI BUILDER FUNCTIONS (HTML Component Generators)
// =============================================================================

function button(label, action, id = '', cls = 'btn-light') {
  return `<button class="btn ${cls}" type="button" data-action="${action}" data-id="${escapeHtml(id)}">${label}</button>`;
}
function input(name, label, value = '', type = 'text', attrs = '') {
  return `<div><label class="form-label" for="f-${name}">${label}</label><input class="form-control" id="f-${name}" name="${name}" type="${type}" value="${escapeHtml(value)}" ${attrs}></div>`;
}
function select(name, label, options, value = '', attrs = '') {
  return `<div><label class="form-label" for="f-${name}">${label}</label><select class="form-select" id="f-${name}" name="${name}" ${attrs}>${options
    .map((o) => {
      const [v, t] = Array.isArray(o) ? o : [o, o];
      return `<option value="${escapeHtml(v)}" ${String(v) === String(value) ? 'selected' : ''}>${escapeHtml(t)}</option>`;
    })
    .join('')}</select></div>`;
}
function area(name, label, value = '', max = 2000) {
  return `<div class="full"><label class="form-label" for="f-${name}">${label}</label><textarea class="form-control" id="f-${name}" name="${name}" maxlength="${max}">${escapeHtml(value)}</textarea></div>`;
}
function submit(label = 'Save') {
  return `<div class="full actions"><button class="btn btn-primary" type="submit">${label}</button></div>`;
}
function empty(title, text, link = '', label = 'Explore destinations') {
  return `<div class="empty"><h2>${title}</h2><p>${text}</p>${link ? `<a class="btn btn-primary" href="#${link}">${label}</a>` : ''}</div>`;
}
function heading(title, text = '', action = '') {
  return `<div class="page-heading"><div><h1>${title}</h1>${text ? `<p>${text}</p>` : ''}</div>${action}</div>`;
}
function openDialog(title, content) {
  modal.innerHTML = `<div class="modal-head"><h2 id="dialog-title">${escapeHtml(title)}</h2><button type="button" class="close-dialog" data-action="close" aria-label="Close dialog">×</button></div>${content}`;
  modal.showModal();
}
function landing() {
  clearInterval(clockTimer);
  if (modal.open) modal.close();
  const destination = state.user ? '#home' : '#login',
    label = state.user ? 'Open dashboard' : 'Start planning';
  app.innerHTML = `<div class="landing"><header class="landing-nav"><a class="brand" href="#welcome" aria-label="Roam home"><span class="brand-mark">↗</span>roam.</a><nav aria-label="About Roam"><a href="#welcome?section=features">Features</a><a href="#welcome?section=how">How it works</a><a href="#welcome?section=questions">FAQs</a></nav><a class="btn btn-primary" href="${destination}">${state.user ? 'Dashboard' : 'Sign in'} ↗</a></header>
<main id="main" tabindex="-1"><section class="landing-hero"><div class="landing-intro"><p class="small-label">A little planning. A world of possibilities.</p><h1>Less figuring<br>it out.<br><em>More getting<br>out there.</em></h1><p class="landing-lead">Meet Roam, your personal trip planner. Discover your next destination, put the details in place, and keep every part of your journey together.</p><div class="landing-actions"><a class="btn btn-primary" href="${destination}">${label} ↗</a><a class="landing-text-link" href="#welcome?section=how">See how it works ↓</a></div><p class="landing-caption">From your first “what if” to your favorite memory.</p></div><div class="landing-visual"><img src="assets/mountains.jpg" alt="Green mountain slopes beneath a dramatic alpine skyline" fetchpriority="high"><div class="landing-image-label"><span>THE NEXT CHAPTER</span><strong>Somewhere worth<br>making time for.</strong></div><div class="landing-trip-note"><span class="landing-note-icon" aria-hidden="true">✓</span><div><strong>A plan that feels like you.</strong><span>Your places. Your pace. Your budget.</span></div></div></div></section>
<div class="landing-strip"><span>Discover with curiosity</span><span aria-hidden="true">✳</span><span>Plan with confidence</span><span aria-hidden="true">✳</span><span>Remember the good parts</span></div>
<section id="landing-features" class="landing-section"><div class="landing-section-heading"><div><p class="small-label">Everything in one place</p><h2>Big adventures.<br>Little details, handled.</h2></div><p>Give every idea a place to grow, with practical tools for before, during, and after your trip.</p></div><div class="landing-feature-grid">
${[
  [
    '◎',
    'Find your kind of somewhere',
    'Explore destinations by season and travel style. Find a match for your interests, budget, and available days.',
  ],
  [
    '⇄',
    'Make the choice yours',
    'Compare up to three destinations by cost, season, activities, and attractions. Save your favorites to a wishlist.',
  ],
  [
    '▤',
    'Turn someday into a plan',
    'Choose your dates and fellow travelers, then edit your generated day-by-day itinerary to suit your pace.',
  ],
  [
    '₹',
    'Keep your budget in view',
    'Plan costs across six categories, record expenses, and see how your spending compares with your budget.',
  ],
  [
    '✓',
    'Leave with a lighter mind',
    'Build and edit categorized packing checklists, tick off essentials, and count down to your next departure.',
  ],
  [
    '♡',
    'Keep more than the itinerary',
    'Save photos and stories from completed trips, share feedback, and revisit your travel history from your profile.',
  ],
]
  .map(
    ([i, t, d]) =>
      `<article class="landing-feature"><span class="landing-feature-icon" aria-hidden="true">${i}</span><h3>${t}</h3><p>${d}</p></article>`,
  )
  .join('')}
</div></section>
<section id="landing-how" class="landing-how landing-section"><div class="landing-how-image"><img src="assets/coast.jpg" alt="A peaceful coastline inviting a slower kind of escape" loading="lazy"><span>Make room for a change of scenery.</span></div><div><p class="small-label">Your next trip, in three steps</p><h2>A good plan.<br>A great place to start.</h2><ol class="landing-steps"><li><h3>Find your inspiration</h3><p>Browse seasonal escapes, compare the possibilities, and save the places that stay on your mind.</p></li><li><h3>Make it your own</h3><p>Sign in or create an account. Choose your dates, set a budget, and shape your itinerary and packing list.</p></li><li><h3>Enjoy the journey</h3><p>Keep your plans close, track expenses, and come back to add the moments you want to remember.</p></li></ol><a class="landing-text-link" href="${destination}">Your next chapter starts here ↗</a></div></section>
<section class="landing-section landing-admin"><div><p class="small-label">Thoughtfully managed</p><h2>A better experience,<br>behind every journey.</h2></div><div><p>Roam brings travelers and administrators into one connected application, with separate tools for each role.</p><p>Travelers manage their own plans and memories. Administrators curate destinations, manage account access and registration, update announcements and support details, respond to feedback, and review activity.</p><a class="landing-text-link" href="${destination}">One sign-in. Your own workspace. ↗</a></div></section>
<section id="landing-questions" class="landing-section landing-faq"><div><p class="small-label">Before you set off</p><h2>A few things<br>you might wonder.</h2></div><div>${[
    [
      'Can I book flights or hotels here?',
      'Roam is a travel planning workspace. It helps you organize destinations, itineraries, budgets, and memories; it does not sell tickets or make reservations.',
    ],
    [
      'Do I need an account?',
      'You can read about Roam here without signing in. Sign in to use your dashboard and save plans. New travelers can choose Create an account on the sign-in page when registration is enabled.',
    ],
    [
      'Are the destination prices live?',
      'Destination costs are planning estimates in INR, not live quotes. Confirm current prices and availability with providers before booking.',
    ],
    [
      'Can I change my plans later?',
      'Yes. Edit your trip details, itinerary activities, packing items, and expenses in your dashboard. You can also cancel a trip or mark it completed.',
    ],
    [
      'Who can see my trips and photos?',
      'Your personal travel records require authentication and are scoped to your account. Other travelers cannot browse your trips or photos. Administrators have separate tools for managing the application and responding to feedback.',
    ],
  ]
    .map(([q, a]) => `<details><summary>${q}</summary><p>${a}</p></details>`)
    .join('')}</div></section>
<section class="landing-cta"><p class="small-label">Your world. Your way.</p><h2>That trip you keep thinking about?<br>Give it a beginning.</h2><a class="btn" href="${destination}">${label} ↗</a><p>Your travel workspace is one sign-in away.</p></section></main><footer class="landing-footer"><a class="brand" href="#welcome"><span class="brand-mark">↗</span>roam.</a><p>Discover. Plan. Remember.</p><a href="${destination}">${state.user ? 'Back to dashboard' : 'Sign in to your workspace'} ↗</a></footer></div>`;
  const section = new URLSearchParams(currentRoute().split('?')[1] || '').get('section');
  if (['features', 'how', 'questions'].includes(section))
    document.querySelector('#landing-' + section)?.scrollIntoView();
}
function auth() {
  clearInterval(clockTimer);
  if (modal.open) modal.close();
  const reg = state.authMode === 'register';
  app.innerHTML = `<div class="auth-layout"><section class="auth-art"><img src="assets/mountains.jpg" alt="Mountain landscape"><a class="brand" href="#welcome"><span class="brand-mark">↗</span>roam.</a><div><div class="small-label text-light">Make room for adventure</div><h1>Good trips begin<br>with a little curiosity.</h1><p>Find your place. Make a plan. Keep the moments that matter.</p></div><p>YOUR WORLD. YOUR WAY.</p></section><main id="main" class="auth-form"><a class="brand" href="#welcome"><span class="brand-mark">↗</span>roam.</a><h2>${reg ? 'Start your next chapter' : 'Welcome back.'}</h2><p class="muted mb-4">${reg ? 'Create your personal travel workspace.' : 'Your next adventure is waiting for you.'}</p><form data-form="${reg ? 'register' : 'login'}">${reg ? input('name', 'Your name', '', 'text', 'required maxlength="100" autocomplete="name"') : ''}${input('email', 'Email address', '', 'email', 'required maxlength="254" autocomplete="email"')}${input('password', 'Password', '', 'password', `required minlength="${reg ? 12 : 1}" maxlength="128" autocomplete="${reg ? 'new' : 'current'}-password"`)}${reg ? '<p class="note">Use at least 12 characters. Your trips and memories stay private.</p>' : ''}<button class="btn btn-primary w-100" type="submit">${reg ? 'Create account' : 'Sign in'} <span aria-hidden="true">↗</span></button></form><p class="auth-switch">${reg ? 'Already have an account?' : 'New to Roam?'} <button data-action="auth-switch">${reg ? 'Sign in' : 'Create an account'}</button></p><p class="note">Planning tools and estimates. This application does not book travel.</p></main></div>`;
}
function shell(content) {
  if (state.user.role === 'ADMIN') {
    managementShell(content);
    return;
  }
  const r = currentRoute().split('/')[0];
  app.innerHTML = `<div class="shell"><aside class="sidebar"><button class="btn btn-light mobile-menu mb-3" data-action="menu" aria-label="Close navigation">Close ×</button><a class="brand" href="#home"><span class="brand-mark">↗</span>roam.</a><nav aria-label="Main navigation">${nav.map(([id, label], i) => `${[0, 4, 10, 13].includes(i) ? `<div class="nav-group">${{ 0: 'Discover', 4: 'Make it happen', 10: 'Your journey', 13: 'Account' }[i]}</div>` : ''}<a href="#${id}" class="${r === id ? 'active' : ''}" ${r === id ? 'aria-current="page"' : ''}><span class="nav-icon" aria-hidden="true">${icon[i]}</span>${label}</a>`).join('')}<div class="nav-group">Travel packages</div><a href="#packages">Explore packages</a><a href="#bookings">My bookings</a>${state.user.role === 'ADMIN' ? `<div class="nav-group">Administration</div><a class="${r === 'admin' ? 'active' : ''}" href="#admin">⚙ Manage experience</a>` : ''}</nav><div class="sidebar-footer">A little planning.<br>A world of possibilities.<p class="mt-3">Support: ${escapeHtml(state.data.settings.support_email)}</p></div></aside><div class="workspace"><header class="topbar"><button class="btn btn-light mobile-menu" data-action="menu" aria-label="Open navigation">☰</button><span class="eyebrow">Your personal travel workspace</span><a class="top-user text-decoration-none" href="#profile"><span class="avatar">${escapeHtml(state.user.name.slice(0, 1).toUpperCase())}</span><span class="name">${escapeHtml(state.user.name)}</span></a></header><main id="main" class="content" tabindex="-1">${content}</main></div></div>`;
}
function card(d) {
  const saved = state.data.wishlist.some((w) => w.id === d.id);
  const isCompared = state.compare.has(Number(d.id));
  return `<article class="destination-card"><div class="card-image"><img loading="lazy" src="${escapeHtml(destinationImage(d.image))}" alt="${escapeHtml(d.category)} travel inspiration"><span class="season-pill">${escapeHtml(d.season)} escape</span></div><div class="card-body"><h3>${escapeHtml(d.name)}</h3><p>${escapeHtml(d.region)}</p><div class="card-bottom"><div><strong>${formatMoney(d.daily_cost)}</strong><small> / person / day</small></div><span class="pill">${d.duration} days</span></div><div class="card-actions">${button('Explore ↗', 'detail', d.id, 'btn-primary btn-sm')}${button(saved ? '♥ Saved' : '♡ Save', 'wish', d.id, 'btn-light btn-sm')}${button(isCompared ? '✓ Compare' : '⇄ Compare', 'compare-add', d.id, isCompared ? 'btn-primary btn-sm' : 'btn-light btn-sm')}</div></div></article>`;
}
function nextTrip() {
  return state.data.trips
    .filter((t) => t.status === 'PLANNED' && t.end_date >= localDate())
    .sort((a, b) => a.start_date.localeCompare(b.start_date))[0];
}
function home() {
  const ts = state.data.trips,
    next = nextTrip(),
    total = ts
      .filter((t) => t.status !== 'CANCELLED')
      .reduce((s, t) => s + Number(t.estimated_total), 0);
  return (
    heading(
      `Hello, ${escapeHtml(state.user.name.split(' ')[0])}.`,
      'Where will your curiosity take you next?',
      `<a class="btn btn-primary" href="#plan">＋ Plan a trip</a>`,
    ) +
    `<section class="hero"><div class="hero-copy"><div class="small-label mb-3">A world worth exploring</div><h2>${escapeHtml(state.data.settings.announcement)}</h2><p>Discover a new favorite place and turn a someday idea into a day-by-day plan.</p><a class="btn" href="#explore">Find your next escape ↗</a></div><img src="assets/mountains.jpg" alt="Alpine peaks and green mountain slopes"></section><div class="stats"><div class="stat"><span>Trips in your story</span><strong>${ts.length}</strong><span>${ts.filter((t) => t.status === 'COMPLETED').length} completed adventures</span></div><div class="stat"><span>Places on your wishlist</span><strong>${state.data.wishlist.length}</strong><span>Ideas for your next chapter</span></div><div class="stat"><span>Planned expenses</span><strong>${formatMoney(total)}</strong><span>Across non-cancelled trips</span></div></div><div class="section-head"><h2>Your next adventure</h2><a href="#itinerary">All trips ↗</a></div>${next ? tripRow(next) : empty('Your calendar is open', 'Pick a destination and we’ll help you put the details together.', 'plan', 'Plan your first trip')}<form data-form="search" class="panel mt-4"><label class="form-label" for="quick-search">A place on your mind?</label><div class="d-flex gap-2"><input id="quick-search" class="form-control" name="q" placeholder="Search destinations" maxlength="100"><button class="btn btn-primary">Search</button></div></form><div class="section-head"><h2>A little inspiration</h2><a href="#explore">Explore all ↗</a></div><div class="cards">${state.data.destinations.slice(0, 3).map(card).join('')}</div><div class="section-head"><h2>Saved for someday</h2><a href="#wishlist">Your wishlist ↗</a></div>${state.data.wishlist.length ? `<div class="cards">${state.data.wishlist.slice(0, 3).map(card).join('')}</div>` : empty('Keep your favorite places close', 'Tap Save on a destination to keep it here.', 'explore')}`
  );
}
function tripRow(t) {
  return `<article class="trip-row"><div><span class="pill ${t.status.toLowerCase()}">${escapeHtml(t.status)}</span><h3>${escapeHtml(t.title)}</h3><p>${escapeHtml(t.destination)} · ${formatDate(t.start_date)} – ${formatDate(t.end_date)} · ${t.people} traveler${t.people > 1 ? 's' : ''}</p></div><a class="btn btn-outline-primary" href="#itinerary/${t.id}">View trip ↗</a></article>`;
}
function explore(params = {}) {
  const q = String(params.q || '').toLowerCase(),
    season = params.season || '',
    category = params.category || '';
  const list = state.data.destinations.filter(
    (d) =>
      (!q || `${d.name} ${d.region} ${d.activities}`.toLowerCase().includes(q)) &&
      (!season || d.season === season) &&
      (!category || d.category === category),
  );
  return (
    heading(
      'Find your somewhere.',
      'Mountains, coastlines, city streets. There’s a place for every kind of trip.',
    ) +
    `<form data-form="filter" class="filterbar">${input('q', 'Search destinations', params.q || '', 'search', 'placeholder="Where do you want to go?" maxlength="100"')}${select('season', 'Best season', [['', 'All seasons'], 'Summer', 'Monsoon', 'Autumn', 'Winter'], season)}${select('category', 'Trip type', [['', 'All types'], ...destinationCategories()], category)}<button class="btn btn-primary">Apply</button></form><p class="note">${list.length} destinations · Sample estimates in INR; confirm current prices before travel.</p><div class="cards">${list.map(card).join('')}</div>${list.length ? '' : empty('No destinations match', 'Try another season, category or search term.')}`
  );
}
function season() {
  return (
    heading('Every season, a new story.', 'Choose the feeling. Find the place.') +
    `<div class="season-grid">${[
      ['Summer', '☀', 'High trails & cool escapes'],
      ['Monsoon', '☂', 'Green hills & rainy days'],
      ['Autumn', '❧', 'Clear skies & golden light'],
      ['Winter', '❄', 'Coastal days & culture'],
    ]
      .map(
        ([s, i, t]) =>
          `<a class="season-tile" data-season="${s}" href="#explore?season=${s}"><b aria-hidden="true">${i}</b><h2>${s}</h2><p class="muted mb-0">${t}</p></a>`,
      )
      .join(
        '',
      )}</div><p class="note">Season suitability is curated by the administrator. Always check weather and travel conditions.</p>`
  );
}
function matching(body) {
  const interests = body.interests
    .toLowerCase()
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  const results = state.data.destinations
    .map((d) => {
      const cost = Number(d.daily_cost) * Number(body.duration),
        matches = interests.filter((i) => d.activities.toLowerCase().includes(i));
      let score =
        (d.season === body.season ? 35 : 0) +
        (d.category === body.category ? 25 : 0) +
        (cost <= Number(body.budget) ? 25 : 0) +
        (Math.abs(d.duration - Number(body.duration)) <= 2 ? 10 : 0) +
        (matches.length ? 5 : 0);
      return { d, score, cost };
    })
    .sort((a, b) => b.score - a.score)
    .slice(0, 3);
  return `<h2 class="mb-3">Your closest matches</h2><p class="note">Ranked by season (35), trip type (25), budget (25), duration (10), and interests (5). Estimates exclude travel to the destination.</p><div class="cards">${results.map(({ d, score, cost }) => `<div><p class="pill">${score}/100 match · ${formatMoney(cost)} for ${Number(body.duration)} days</p>${card(d)}</div>`).join('')}</div>`;
}
function perfect() {
  return (
    heading('Find your kind of adventure.', 'A few preferences. A shortlist that feels like you.') +
    `<form data-form="perfect" class="panel form-grid">${select('season', 'Season', ['Summer', 'Monsoon', 'Autumn', 'Winter'], state.perfectQuery?.season)}${select('category', 'Trip type', destinationCategories(), state.perfectQuery?.category)}${input('budget', 'Total budget per person (INR)', state.perfectQuery?.budget || '20000', 'number', 'min="1" max="99999999" required')}${input('duration', 'Duration in days', state.perfectQuery?.duration || '5', 'number', 'min="1" max="60" required')}${input('interests', 'Interests (comma separated)', state.perfectQuery?.interests || 'Nature, Food', 'text', 'maxlength="500" placeholder="Nature, Culture, Food"')}${submit('Find my matches ↗')}</form><div id="matches">${state.perfectQuery ? matching(state.perfectQuery) : ''}</div>`
  );
}
function plan(t = null, selected = '') {
  const ds = [...state.data.destinations];
  if (t && !ds.some((d) => Number(d.id) === Number(t.destination_id))) {
    const old = state.data.trips.find((x) => Number(x.id) === Number(t.id));
    ds.unshift({
      id: t.destination_id,
      name: (old?.destination || 'Current destination') + ' (no longer published)',
    });
  }
  return (
    heading(
      t ? 'Edit your trip' : 'Let’s make it a trip.',
      'Start with the essentials. Refine the details as you go.',
    ) +
    (!ds.length
      ? empty('No destinations available', 'Please check again later.')
      : `<form data-form="trip" class="panel form-grid">${t ? `<input type="hidden" name="id" value="${t.id}">` : ''}${input('title', 'Trip name', t?.title || '', 'text', 'required maxlength="150" placeholder="A long weekend away"')}${select(
          'destination_id',
          'Destination',
          ds.map((d) => [d.id, d.name]),
          t?.destination_id || selected,
          'required',
        )}${input('start_date', 'Departure date', t?.start_date || localDate(), 'date', 'required')}${input('end_date', 'Return date', t?.end_date || localDate(), 'date', 'required')}${input('people', 'Travelers', t?.people || 1, 'number', 'min="1" max="50" required')}${input('budget', 'Total group budget (INR)', t?.budget ?? 10000, 'number', 'min="0" max="99999999.99" step="0.01" required')}${area('interests', 'Interests & preferences', t?.interests || '', 500)}${
          t
            ? ''
            : select(
                'itinerary_mode',
                'Build your itinerary',
                [
                  ['suggested', 'Start with suggested activities'],
                  ['blank', 'Start with a blank itinerary'],
                ],
                'suggested',
              )
        }<p class="note full">Trips can be up to 60 days. Add, edit or remove itinerary activities to make the schedule your own. Your seasonal packing list is editable too. No reservations are made.</p>${submit(t ? 'Save changes' : 'Create my trip ↗')}</form>`)
  );
}
function tripTabs(t, active) {
  return `<div class="tabs"><a href="#itinerary/${t.id}" class="${active === 'itinerary' ? 'active' : ''}">Itinerary</a><a href="#budget/${t.id}" class="${active === 'budget' ? 'active' : ''}">Budget</a><a href="#packing/${t.id}" class="${active === 'packing' ? 'active' : ''}">Packing</a></div>`;
}
function tripPage(t, tab) {
  const writable = t.status === 'PLANNED';
  const base =
    heading(
      escapeHtml(t.title),
      `${formatDate(t.start_date)} – ${formatDate(t.end_date)} · ${tripDurationDays(t)} days · ${t.people} travelers`,
      button('Print / PDF', 'print', '', 'btn-light'),
    ) + tripTabs(t, tab);
  if (tab === 'budget') {
    const total = t.expenses.reduce((s, e) => s + Number(e.amount), 0),
      remaining = Number(t.budget) - total;
    return (
      base +
      `<div class="budget-total"><p>Total estimated expenses</p><strong>${formatMoney(total)}</strong><p>${remaining < 0 ? `${formatMoney(-remaining)} over budget` : `${formatMoney(remaining)} remaining`} · Group budget ${formatMoney(t.budget)}</p></div><div class="panel"><div class="section-head mt-0"><h2>Your expense plan</h2>${writable ? button('＋ Add expense', 'expense-new', t.id, 'btn-primary') : ''}</div>${t.expenses.length ? `<div class="table-responsive"><table class="table"><thead><tr><th>Category</th><th>Expense</th><th>Group amount</th><th class="no-print">Actions</th></tr></thead><tbody>${t.expenses.map((e) => `<tr><td>${escapeHtml(e.category)}</td><td>${escapeHtml(e.label)}</td><td>${formatMoney(e.amount)}</td><td class="actions">${writable ? button('Edit', 'expense-edit', e.id, 'btn-light btn-sm') + button('Remove', 'expense-delete', e.id, 'btn-light btn-sm') : ''}</td></tr>`).join('')}</tbody></table></div>` : empty('No expenses yet', 'Add transport, hotel, food, activities, shopping and other estimates.')}<p class="note mt-3">All entries are total group costs in INR, not per-person rates.</p>${writable ? button('Change budget limit', 'trip-edit', t.id) : ''}</div>`
    );
  }
  if (tab === 'packing') {
    const done = t.packing.filter((p) => p.packed).length;
    return (
      base +
      `<div class="panel"><div class="section-head mt-0"><div><h2>Pack a little peace of mind.</h2><p class="muted mb-0">${done} of ${t.packing.length} items packed</p></div>${writable ? button('＋ Add item', 'packing-new', t.id, 'btn-primary') : ''}</div>${t.packing.map((p) => `<div class="check-row"><input id="pack-${p.id}" type="checkbox" data-pack="${p.id}" ${p.packed ? 'checked' : ''} ${writable ? '' : 'disabled'}><label for="pack-${p.id}">${escapeHtml(p.label)}<small class="d-block muted">${escapeHtml(p.category)}</small></label>${writable ? button('Edit', 'packing-edit', p.id, 'btn-light btn-sm') + button('Remove', 'packing-delete', p.id, 'btn-light btn-sm') : ''}</div>`).join('') || empty('Your checklist is empty', 'Add the essentials you want to bring.')}</div>`
    );
  }
  const grouped = {};
  for (const item of t.itinerary) (grouped[item.day_number] ??= []).push(item);
  return (
    base +
    `<div class="panel"><div class="section-head mt-0"><div><span class="pill ${t.status.toLowerCase()}">${t.status}</span><h2 class="mt-2">One day at a time.</h2></div>${writable ? button('＋ Add activity', 'itinerary-new', t.id, 'btn-primary') : ''}</div><p class="muted">${escapeHtml(t.interests) || 'Make space for the unexpected.'}</p>${
      Object.entries(grouped)
        .map(
          ([day, items]) =>
            `<div class="timeline-day"><div class="day-label">DAY ${day}</div><div>${items.map((i) => `<article class="timeline-item"><time>${escapeHtml(i.time_slot)}</time><h3 class="mt-2">${escapeHtml(i.title)}</h3><p class="muted">${escapeHtml(i.note)}</p>${writable ? `<div class="actions">${button('Edit', 'itinerary-edit', i.id, 'btn-light btn-sm')}${button('Remove', 'itinerary-delete', i.id, 'btn-light btn-sm')}</div>` : ''}</article>`).join('')}</div></div>`,
        )
        .join('') ||
      empty('A blank page for your adventure', 'Add an activity to begin your itinerary.')
    }<div class="actions mt-4">${writable ? button('Edit trip details', 'trip-edit', t.id) + button('Complete trip', 'trip-complete', t.id, 'btn-outline-primary') + button('Cancel trip', 'trip-cancel', t.id, 'btn-outline-danger') : ''}<a class="btn btn-light" href="#itinerary">All trips</a></div></div>`
  );
}
function tripList(tab) {
  return (
    heading(
      tab === 'itinerary'
        ? 'Your trips, all together.'
        : tab === 'budget'
          ? 'Make every rupee count.'
          : 'Ready, set, packed.',
      tab === 'itinerary'
        ? 'Plans to look forward to. Stories to look back on.'
        : 'Choose a trip to get started.',
      `<a class="btn btn-primary" href="#plan">＋ Plan a trip</a>`,
    ) +
    (state.data.trips.length
      ? state.data.trips
          .map((t) => tripRow(t).replace(`#itinerary/${t.id}`, `#${tab}/${t.id}`))
          .join('')
      : empty(
          'Your first adventure starts here',
          'Create a trip to unlock your itinerary, budget and packing list.',
          'plan',
          'Plan a trip',
        ))
  );
}
function compare() {
  const list = [...state.compare].map(findDestination).filter(Boolean);
  const fields = [
    ['region', 'Location'],
    ['daily_cost', 'Daily estimate / person'],
    ['season', 'Best season'],
    ['duration', 'Suggested days'],
    ['activities', 'Activities'],
    ['attractions', 'Attractions'],
  ];
  const availableDestinations = (state.data?.destinations || []).filter(
    (d) => !state.compare.has(Number(d.id)),
  );
  const addForm =
    state.compare.size < 3
      ? availableDestinations.length
        ? `<div class="panel"><form data-form="compare" class="form-grid">${select(
            'destination_id',
            'Add a destination to compare',
            availableDestinations.map((d) => [d.id, d.name]),
          )}${submit('Add to comparison')}</form></div>`
        : `<div class="panel"><p class="note">All available destinations are in the comparison.</p></div>`
      : `<div class="panel"><p class="note">Maximum of 3 destinations selected for comparison. Remove one to add another.</p></div>`;

  return (
    heading('A closer look, side by side.', 'Choose two or three destinations to compare.') +
    addForm +
    (list.length >= 2
      ? `<div class="panel table-responsive"><table class="table compare-table"><thead><tr><th>Destination</th>${list
          .map(
            (d) =>
              `<th>${escapeHtml(d.name)} ${button('×', 'compare-remove', d.id, 'btn-light btn-sm')}</th>`,
          )
          .join('')}</tr></thead><tbody>${fields
          .map(
            ([k, l]) =>
              `<tr><th>${l}</th>${list
                .map((d) => `<td>${k === 'daily_cost' ? formatMoney(d[k]) : escapeHtml(d[k])}</td>`)
                .join('')}</tr>`,
          )
          .join('')}<tr><th>Your next step</th>${list
          .map(
            (d) =>
              `<td><a class="btn btn-primary" href="#plan/${d.id}">Plan ${escapeHtml(d.name)}</a></td>`,
          )
          .join('')}</tr></tbody></table></div>`
      : empty(
          `${list.length} of 2–3 places selected`,
          'Add another destination to see the comparison.',
        )) +
    (list.length < 2 && list.length > 0
      ? list
          .map(
            (d) =>
              `<div class="panel d-flex justify-content-between align-items-center"><span><strong>${escapeHtml(d.name)}</strong> (${escapeHtml(d.region)})</span>${button('Remove', 'compare-remove', d.id, 'btn-light btn-sm')}</div>`,
          )
          .join('')
      : '')
  );
}
function syncMemoryDate(form) {
  const trip = state.data.trips.find(
    (t) => String(t.id) === String(form.elements.trip_id.value) && t.status === 'COMPLETED',
  );
  if (!trip) return;
  const field = form.elements.memory_date;
  field.min = trip.start_date;
  field.max = trip.end_date;
  if (!field.value || field.value < field.min || field.value > field.max)
    field.value = trip.end_date;
}
function memories() {
  const trips = state.data.trips.filter((t) => t.status === 'COMPLETED');
  return (
    heading(
      'Keep the feeling.',
      'A personal collection of places, people and little moments.',
      trips.length ? button('＋ Add a memory', 'memory-new', '', 'btn-primary') : '',
    ) +
    (state.data.memories.length
      ? `<div class="gallery">${state.data.memories.map((m) => `<article class="panel">${m.has_photo ? `<img loading="lazy" src="api/photo?id=${m.id}" alt="${escapeHtml(m.title)}">` : ''}<span class="small-label">${formatDate(m.memory_date)}</span><h2 class="mt-2">${escapeHtml(m.title)}</h2><p class="note">${escapeHtml(m.trip_title)}</p><p>${escapeHtml(m.note)}</p>${button('Delete memory', 'memory-delete', m.id, 'btn-light btn-sm')}</article>`).join('')}</div>`
      : empty(
          'Your stories belong here',
          trips.length
            ? 'Add your first memory from a completed trip.'
            : 'After a trip ends, mark it complete in My itinerary to add photos and notes.',
          'itinerary',
          'View trips',
        ))
  );
}
function countdown() {
  const t = nextTrip();
  return (
    heading('The adventure is getting closer.', 'A little anticipation goes a long way.') +
    (t
      ? `<div class="countdown"><div class="small-label text-light">Next stop</div><h2 class="mt-3">${escapeHtml(t.destination)}</h2><p>${escapeHtml(t.title)} · ${formatDate(t.start_date)}</p><div id="clock" class="clock" data-start="${t.start_date}"></div><a class="btn btn-light" href="#itinerary/${t.id}">Open itinerary ↗</a><p class="note text-light mt-4">Countdown to midnight on your departure date, using your device’s time zone.</p></div>`
      : empty(
          'Something to look forward to',
          'Plan your next trip and watch the countdown begin.',
          'plan',
          'Plan a trip',
        ))
  );
}
function tick() {
  const el = document.querySelector('#clock');
  if (!el) return;
  const seconds = Math.max(
    0,
    Math.floor((new Date(el.dataset.start + 'T00:00:00') - new Date()) / 1000),
  );
  el.innerHTML =
    seconds === 0
      ? '<h2>Your adventure is underway!</h2>'
      : [
          [Math.floor(seconds / 86400), 'Days'],
          [Math.floor(seconds / 3600) % 24, 'Hours'],
          [Math.floor(seconds / 60) % 60, 'Minutes'],
          [seconds % 60, 'Seconds'],
        ]
          .map(
            ([n, l]) =>
              `<div><strong>${String(n).padStart(2, '0')}</strong><span>${l}</span></div>`,
          )
          .join('');
}
function feedback() {
  const available = state.data.trips.filter(
    (t) => t.status === 'COMPLETED' && !state.data.feedback.some((f) => f.trip_id === t.id),
  );
  return (
    heading('How was your journey?', 'Your feedback helps us make the next one better.') +
    (available.length
      ? `<form data-form="feedback" class="panel form-grid">${select(
          'trip_id',
          'Completed trip',
          available.map((t) => [t.id, t.title]),
        )}${select('rating', 'Your rating', [
          [5, '5 — Excellent'],
          [4, '4 — Great'],
          [3, '3 — Good'],
          [2, '2 — Could be better'],
          [1, '1 — Disappointing'],
        ])}${area('message', 'Tell us about your experience')}${submit('Submit feedback')}</form>`
      : empty('You’re all caught up', 'You can review each completed trip once.')) +
    state.data.feedback
      .map(
        (f) =>
          `<article class="panel"><span class="pill">${f.rating} / 5</span><h2 class="mt-3">${escapeHtml(f.title)}</h2><p>${escapeHtml(f.message)}</p>${f.response ? `<hr><h3>Response from the team</h3><p>${escapeHtml(f.response)}</p>` : '<p class="note">Thank you. Your feedback has been shared with the team.</p>'}</article>`,
      )
      .join('')
  );
}
function profile() {
  return (
    heading('A little about you.', 'Keep your account details up to date.') +
    `<form data-form="profile" class="panel form-grid">${input('name', 'Your name', state.user.name, 'text', 'required maxlength="100"')}${input('email', 'Email address', state.user.email, 'email', 'required maxlength="254"')}${submit('Save profile')}</form><form data-form="password" class="panel form-grid"><h2 class="full">Change password</h2>${input('current_password', 'Current password', '', 'password', 'required maxlength="128" autocomplete="current-password"')}${input('password', 'New password', '', 'password', 'required minlength="12" maxlength="128" autocomplete="new-password"')}<p class="note full">Use 12–128 characters. Changing your password signs out all sessions.</p>${submit('Change password')}</form><div class="panel"><h2>Your travel story</h2><p>${state.data.trips.length} trips · ${state.data.wishlist.length} saved places</p><div class="actions"><a href="#itinerary" class="btn btn-light">Trip history</a><a href="#wishlist" class="btn btn-light">Wishlist</a></div></div>`
  );
}
function adminPager(kind) {
  const p = state.admin.pagination,
    current = p[kind + '_page'],
    total = p[kind + '_total'];
  const url = (n) => {
    const q = new URLSearchParams({
      q: p.q,
      user_page: p.user_page,
      feedback_page: p.feedback_page,
    });
    q.set(kind + '_page', n);
    return '#admin?' + q;
  };
  return `<div class="actions my-3">${current > 1 ? `<a class="btn btn-light" href="${escapeHtml(url(current - 1))}">Previous</a>` : ''}<span class="note">Page ${current} · ${total} records</span>${current * 50 < total ? `<a class="btn btn-light" href="${escapeHtml(url(current + 1))}">Next</a>` : ''}</div>`;
}
function admin() {
  const a = state.admin;
  return (
    heading(
      'Make every journey better.',
      'Manage the destinations and experience your travelers see.',
      button('＋ Destination', 'destination-new', '', 'btn-primary'),
    ) +
    `<div class="stats"><div class="stat"><span>Registered users</span><strong>${a.stats.users}</strong></div><div class="stat"><span>Trips created</span><strong>${a.stats.trips}</strong></div><div class="stat"><span>Average feedback</span><strong>${a.stats.rating ? Number(a.stats.rating).toFixed(1) + ' / 5' : '—'}</strong></div></div><div class="panel"><h2>Experience settings</h2><form data-form="admin-settings" class="form-grid">${area('announcement', 'Dashboard message', a.settings.announcement)}${input('support_email', 'Support email', a.settings.support_email, 'email', 'required maxlength="254"')}${select(
      'registration_enabled',
      'New registrations',
      [
        ['true', 'Open'],
        ['false', 'Closed'],
      ],
      a.settings.registration_enabled,
    )}${submit('Save experience settings')}</form></div><div class="panel"><h2>Destination catalog</h2><p class="note">Unpublish a destination to remove it from discovery. Existing trips are preserved.</p><div class="table-responsive"><table class="table"><thead><tr><th>Destination</th><th>Season</th><th>Daily estimate</th><th>Visibility</th><th>Action</th></tr></thead><tbody>${a.destinations.map((d) => `<tr><td>${escapeHtml(d.name)}</td><td>${escapeHtml(d.season)}</td><td>${formatMoney(d.daily_cost)}</td><td>${d.active ? 'Published' : 'Hidden'}</td><td>${button('Edit', 'destination-edit', d.id, 'btn-light btn-sm')}</td></tr>`).join('')}</tbody></table></div></div><div class="panel"><h2>User access</h2><p class="note">Disabling an account immediately revokes its sessions.</p><form data-form="admin-search" class="d-flex gap-2">${input('q', 'Search users by name or email', a.pagination.q, 'search', 'maxlength="100"')}<button class="btn btn-primary align-self-end">Search</button></form>${adminPager('user')}<div class="table-responsive"><table class="table"><thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th>Action</th></tr></thead><tbody>${a.users.map((u) => `<tr><td>${escapeHtml(u.name)}</td><td>${escapeHtml(u.email)}</td><td>${escapeHtml(u.role)}</td><td>${u.active ? 'Active' : 'Disabled'}</td><td>${u.role === 'CLIENT' ? button(u.active ? 'Disable' : 'Enable', 'user-toggle', u.id, 'btn-light btn-sm') : 'Operator managed'}</td></tr>`).join('')}</tbody></table></div></div><div class="panel"><h2>Traveler feedback</h2>${adminPager('feedback')}${a.feedback.map((f) => `<article class="border-bottom py-3"><span class="pill">${f.rating} / 5</span><h3 class="mt-2">${escapeHtml(f.title)} · ${escapeHtml(f.name)}</h3><p>${escapeHtml(f.message)}</p>${f.response ? `<p class="muted">Team: ${escapeHtml(f.response)}</p>` : ''}${button('Respond', 'feedback-reply', f.id, 'btn-light btn-sm')}</article>`).join('') || '<p class="muted">No feedback received yet.</p>'}</div><div class="panel"><h2>Recent admin activity</h2><div class="table-responsive"><table class="table"><thead><tr><th>Action</th><th>Administrator</th><th>Time (server)</th></tr></thead><tbody>${a.audit.map((a) => `<tr><td>${escapeHtml(a.action)} · #${a.target_id}</td><td>${escapeHtml(a.name)}</td><td>${escapeHtml(a.created_at)}</td></tr>`).join('')}</tbody></table></div></div>`
  );
}
// =============================================================================
// 5. CLIENT-SIDE ROUTER & VIEW RENDERER (render)
// =============================================================================

/**
 * Main application router and view orchestrator:
 * - Checks user authentication state and current URL hash
 * - Loads required data from /api/* endpoints
 * - Renders the appropriate layout (Landing, Auth, Client Dashboard, or Admin Console)
 */
async function render() {
  const generation = ++renderGeneration;
  clearInterval(clockTimer);
  if (
    currentRoute().split('?')[0] === 'welcome' ||
    (!state.user && ['home', 'main'].includes(currentRoute()))
  ) {
    landing();
    return;
  }
  if (!state.user) {
    if (currentRoute() === 'login') state.authMode = 'login';
    auth();
    return;
  }
  const [path, query = ''] = currentRoute().split('?'),
    [page, id] = path.split('/');
  if (state.user.role === 'ADMIN' && !managementNav.some(([r]) => r === page)) {
    location.hash = 'operations';
    return;
  }
  let content = '';
  try {
    if (
      [
        'operations',
        'admin-packages',
        'admin-hotels',
        'admin-transport',
        'admin-bookings',
      ].includes(page)
    ) {
      const cachedAdminPkg = roamCache.get('roam_admin_packages_cache', CACHE_TTL.ADMIN);
      if (cachedAdminPkg?.data) {
        state.packages = cachedAdminPkg.data;
        content = operationPage(page);
        if (cachedAdminPkg.isStale) {
          api('admin/packages').then((fresh) => {
            if (JSON.stringify(fresh) !== JSON.stringify(state.packages)) {
              state.packages = fresh;
              roamCache.set('roam_admin_packages_cache', fresh);
              const cur = currentRoute().split('?')[0];
              if (['operations','admin-packages','admin-hotels','admin-transport','admin-bookings'].includes(cur)) {
                shell(operationPage(cur));
              }
            }
          }).catch(() => {});
        }
      } else {
        state.packages = await api('admin/packages');
        roamCache.set('roam_admin_packages_cache', state.packages);
        content = operationPage(page);
      }
    } else if (['packages', 'bookings'].includes(page)) {
      const cachedPkg = roamCache.get('roam_packages_cache', CACHE_TTL.PACKAGES);
      if (cachedPkg?.data) {
        state.packages = cachedPkg.data;
        content = page === 'packages' ? packageCards() : clientBookings();
        if (cachedPkg.isStale) {
          api('packages').then((fresh) => {
            if (JSON.stringify(fresh) !== JSON.stringify(state.packages)) {
              state.packages = fresh;
              roamCache.set('roam_packages_cache', fresh);
              const cur = currentRoute().split('?')[0];
              if (['packages', 'bookings'].includes(cur)) {
                shell(cur === 'packages' ? packageCards() : clientBookings());
              }
            }
          }).catch(() => {});
        }
      } else {
        state.packages = await api('packages');
        roamCache.set('roam_packages_cache', state.packages);
        content = page === 'packages' ? packageCards() : clientBookings();
      }
    } else if (['itinerary', 'budget', 'packing'].includes(page) && id) {
      const tripKey = 'roam_trip_' + id;
      const cachedTrip = roamCache.get(tripKey, CACHE_TTL.TRIP);
      if (cachedTrip?.data) {
        state.trip = cachedTrip.data;
        content = tripPage(state.trip, page);
        if (cachedTrip.isStale) {
          api('trip?id=' + encodeURIComponent(id)).then((fresh) => {
            if (JSON.stringify(fresh) !== JSON.stringify(state.trip)) {
              state.trip = fresh;
              roamCache.set(tripKey, fresh);
              const [curPage, curId] = currentRoute().split('?')[0].split('/');
              if (['itinerary', 'budget', 'packing'].includes(curPage) && curId === String(id)) {
                shell(tripPage(state.trip, curPage));
              }
            }
          }).catch(() => {});
        }
      } else {
        state.trip = await api('trip?id=' + encodeURIComponent(id));
        roamCache.set(tripKey, state.trip);
        content = tripPage(state.trip, page);
      }
    } else if (page === 'admin') {
      const adminKey = 'roam_admin_' + query;
      const cachedAdmin = roamCache.get(adminKey, CACHE_TTL.ADMIN);
      if (cachedAdmin?.data) {
        state.admin = cachedAdmin.data;
        content = admin();
        if (cachedAdmin.isStale) {
          api('admin?' + query).then((fresh) => {
            if (JSON.stringify(fresh) !== JSON.stringify(state.admin)) {
              state.admin = fresh;
              roamCache.set(adminKey, fresh);
              const [curPage, curQuery = ''] = currentRoute().split('?');
              if (curPage === 'admin' && curQuery === query) {
                shell(admin());
              }
            }
          }).catch(() => {});
        }
      } else {
        state.admin = await api('admin?' + query);
        roamCache.set(adminKey, state.admin);
        content = admin();
      }
    } else {
      const pages = {
        home,
        login: home,
        season,
        explore: () => explore(Object.fromEntries(new URLSearchParams(query))),
        perfect,
        plan: () => plan(null, id),
        itinerary: () => tripList('itinerary'),
        budget: () => tripList('budget'),
        packing: () => tripList('packing'),
        compare,
        wishlist: () =>
          heading('Saved for someday.', 'Keep your favorite places close.') +
          (state.data.wishlist.length
            ? `<div class="cards">${state.data.wishlist.map(card).join('')}</div>`
            : empty(
                'A world of possibilities',
                'Save a destination while exploring to find it here.',
                'explore',
              )),
        memories,
        countdown,
        feedback,
        profile,
        logout: () =>
          heading('Heading out?', 'Your plans will be here when you get back.') +
          `<div class="panel"><h2>Sign out of this session?</h2><p class="muted">Your saved trips and memories are stored safely in your account.</p><div class="actions">${button('Yes, sign out', 'logout', '', 'btn-primary')}<a class="btn btn-light" href="#home">Stay here</a></div></div>`,
      };
      content = pages[page]
        ? pages[page]()
        : empty('This page isn’t here', 'Return to your dashboard to continue.', 'home', 'Go home');
    }
    if (generation !== renderGeneration) return;
    shell(content);
    if (page === 'countdown') {
      tick();
      clockTimer = setInterval(tick, 1000);
    }
  } catch (e) {
    if (generation === renderGeneration && state.user)
      shell(empty('We couldn’t open this page', escapeHtml(e.message), 'home', 'Go home'));
    toast(e.message, true);
  }
}
/**
 * Bootstraps the application on initial page load with LocalStorage Caching & Stale-While-Revalidate:
 * 1. Instantly hydrates state from LocalStorage cache (0ms perceived load time).
 * 2. Paints the cached view immediately without waiting for server response.
 * 3. Quietly validates session and refreshes data in the background.
 */
async function start() {
  loadCompareState();
  const cachedSession = roamCache.get('roam_session_cache', CACHE_TTL.SESSION);
  const cachedData = roamCache.get('roam_data_cache', CACHE_TTL.DATA);
  let renderedFromCache = false;

  // Step 1: Instant LocalStorage hydration (0 ms render)
  if (cachedSession?.data?.user) {
    state.user = cachedSession.data.user;
    state.csrf = cachedSession.data.csrf || '';
    if (cachedData?.data) {
      state.data = cachedData.data;
      renderedFromCache = true;
      try {
        await render();
      } catch (err) {}
    }
  } else {
    // If not signed in and on public route, render landing immediately in 0ms
    const route = currentRoute().split('?')[0];
    if (['home', 'welcome', ''].includes(route)) {
      landing();
      renderedFromCache = true;
    }
  }

  // Step 2: Background Revalidation (Non-blocking)
  try {
    const s = await api('session');
    const userChanged = JSON.stringify(state.user) !== JSON.stringify(s.user);
    state.user = s.user;
    state.csrf = s.csrf;
    roamCache.set('roam_session_cache', s);

    if (state.user) {
      const dataChanged = await refresh(true);
      if (!renderedFromCache || userChanged || dataChanged) {
        await render();
      }
    } else {
      if (cachedSession?.data?.user) {
        roamCache.clearUserData();
        state.data = null;
        state.trip = null;
        await render();
      } else if (!renderedFromCache) {
        await render();
      }
    }
  } catch (e) {
    if (!renderedFromCache) {
      if (['home', 'welcome'].includes(currentRoute().split('?')[0])) landing();
      else app.innerHTML = empty('We couldn’t connect', escapeHtml(e.message));
      toast(e.message, true);
    }
  }
}

// Re-render whenever the URL hash route changes (#home, #explore, #itinerary, etc.)
window.addEventListener('hashchange', () => {
  if (modal.open) modal.close();
  window.scrollTo(0, 0);
  render();
});
document.querySelector('.skip-link').addEventListener('click', (event) => {
  event.preventDefault();
  document.querySelector('#main')?.focus();
});
function childForm(kind, item = {}) {
  const t = state.trip;
  let fields = `<input type="hidden" name="trip_id" value="${t.id}">${item.id ? `<input type="hidden" name="id" value="${item.id}">` : ''}`;
  if (kind === 'expenses')
    fields +=
      select(
        'category',
        'Category',
        ['Transport', 'Hotel', 'Food', 'Activities', 'Shopping', 'Other'],
        item.category,
      ) +
      input('label', 'Expense', item.label || '', 'text', 'required maxlength="120"') +
      input(
        'amount',
        'Total group amount (INR)',
        item.amount ?? '',
        'number',
        'required min="0" max="99999999.99" step="0.01"',
      );
  if (kind === 'packing')
    fields +=
      select(
        'category',
        'Category',
        ['Clothes', 'Documents', 'Toiletries', 'Electronics', 'Season essentials', 'Other'],
        item.category,
      ) + input('label', 'Item', item.label || '', 'text', 'required maxlength="120"');
  if (kind === 'itinerary')
    fields +=
      input(
        'day_number',
        'Day',
        item.day_number || 1,
        'number',
        `required min="1" max="${tripDurationDays(t)}"`,
      ) +
      input('time_slot', 'Time', item.time_slot || '09:00', 'time', 'required') +
      input('title', 'Activity or place', item.title || '', 'text', 'required maxlength="150"') +
      area('note', 'Notes', item.note || '', 1000);
  openDialog(
    kind === 'itinerary'
      ? item.id
        ? 'Edit activity'
        : 'Add activity'
      : item.id
        ? 'Edit item'
        : 'Add item',
    `<form data-form="${kind}" class="form-grid">${fields}${submit()}</form>`,
  );
}
function destinationImage(image) {
  return /^api\/destination-photo\?id=\d+$/.test(image || '') ? image : 'assets/' + image;
}
function destinationCategories() {
  return [...new Set(state.data.destinations.map((destination) => destination.category))].sort();
}
function destinationForm(d = {}) {
  openDialog(
    d.id ? 'Edit destination' : 'Add destination',
    `<form data-form="admin-destination" class="form-grid">${d.id ? `<input type="hidden" name="id" value="${d.id}">` : ''}${input('name', 'Destination name', d.name || '', 'text', 'required maxlength="120"')}${input('region', 'Region / country', d.region || '', 'text', 'required maxlength="120"')}${area('description', 'Description', d.description || '', 4000)}${select('season', 'Best season', ['Summer', 'Monsoon', 'Autumn', 'Winter'], d.season)}${input('category', 'Category', d.category || '', 'text', 'required maxlength="40" placeholder="e.g. Wildlife, Wellness or Heritage"')}${input('daily_cost', 'Daily estimate per person (INR)', d.daily_cost || 0, 'number', 'required min="0" max="99999999.99" step="0.01"')}${input('duration', 'Suggested days', d.duration || 3, 'number', 'required min="1" max="60"')}${input('activities', 'Activities (comma separated)', d.activities || '', 'text', 'required maxlength="1000"')}${input('attractions', 'Attractions (comma separated)', d.attractions || '', 'text', 'required maxlength="1000"')}${input('photo_file', 'Cover photo (JPEG or PNG, up to 3 MB)', '', 'file', 'accept="image/jpeg,image/png" ' + (d.id ? '' : 'required'))}${d.id ? `<p class="note full">Leave the photo empty to keep the current cover.</p><img class="photo-detail full" src="${escapeHtml(destinationImage(d.image))}" alt="Current destination cover">` : ''}${select(
      'active',
      'Visibility',
      [
        ['true', 'Published'],
        ['false', 'Hidden'],
      ],
      String(d.active ?? true),
    )}${submit('Save destination')}</form>`,
  );
}
async function mutation(path, body, message = 'Saved.') {
  await api(path, body);
  if (modal.open) modal.close();
  if (state.trip?.id) {
    roamCache.remove('roam_trip_' + state.trip.id);
  }
  roamCache.remove('roam_packages_cache');
  roamCache.remove('roam_admin_packages_cache');
  await refresh();
  await render();
  toast(message);
}
function confirmAction(title, text, path, body, label) {
  openDialog(
    title,
    `<p>${escapeHtml(text)}</p><form data-form="confirmed" data-path="${escapeHtml(path)}"><input type="hidden" name="payload" value="${escapeHtml(JSON.stringify(body))}"><div class="actions"><button class="btn btn-primary">${label}</button>${button('Keep it', 'close')}</div></form>`,
  );
}
// =============================================================================
// 6. GLOBAL EVENT LISTENERS & USER INTERACTION HANDLERS
// =============================================================================

/**
 * Handles all declarative data-action button clicks (Modals, Navigation, Trip CRUD, etc.)
 */
document.addEventListener('click', async (event) => {
  const el = event.target.closest('[data-action]');
  if (!el) return;
  const action = el.dataset.action,
    id = Number(el.dataset.id);
  if (action === 'close') {
    modal.close();
    return;
  }
  if (action === 'menu') {
    document.querySelector('.sidebar').classList.toggle('open');
    return;
  }
  if (action === 'auth-switch') {
    state.authMode = state.authMode === 'login' ? 'register' : 'login';
    auth();
    return;
  }
  if (action === 'print') {
    window.print();
    return;
  }
  try {
    if (await packageAction(action, id)) return;
    switch (action) {
      case 'logout':
        await api('logout', {});
        state.user = null;
        state.data = null;
        state.packages = null;
        state.trip = null;
        state.admin = null;
        state.compare.clear();
        state.authMode = 'login';
        roamCache.clearUserData();
        location.hash = 'welcome';
        landing();
        toast('You have been signed out.');
        break;
      case 'detail': {
        const d = findDestination(id) || state.data.wishlist.find((d) => d.id === id);
        openDialog(
          d.name,
          `<img class="photo-detail" src="${escapeHtml(destinationImage(d.image))}" alt="${escapeHtml(d.category)} travel inspiration"><p class="small-label">${escapeHtml(d.region)}</p><p>${escapeHtml(d.description)}</p><div class="form-grid"><div><h3>Best season</h3><p>${escapeHtml(d.season)}</p></div><div><h3>Planning estimate</h3><p>${formatMoney(d.daily_cost)} / person / day</p></div><div><h3>Things to do</h3><p>${escapeHtml(d.activities)}</p></div><div><h3>Places to see</h3><p>${escapeHtml(d.attractions)}</p></div></div><p class="note">Suggested duration: ${d.duration} days. Cover photos are travel inspiration, not verified images of this destination.</p><div class="actions">${d.active ? `<a class="btn btn-primary" href="#plan/${d.id}">Plan this trip ↗</a>` : '<p>This destination is currently unavailable for new trips.</p>'}${button('Save / remove wishlist', 'wish', d.id)}</div>`,
        );
        break;
      }
      case 'wish':
        await mutation(
          'wishlist',
          { destination_id: id, saved: !state.data.wishlist.some((d) => d.id === id) },
          'Wishlist updated.',
        );
        break;
      case 'compare-add':
      case 'compare-remove':
        const targetId = Number(id);
        if (state.compare.has(targetId)) state.compare.delete(targetId);
        else if (state.compare.size < 3) state.compare.add(targetId);
        else throw Error('Compare up to three destinations at a time.');
        saveCompareState();
        await render();
        toast(`${state.compare.size} destination${state.compare.size === 1 ? '' : 's'} selected for comparison.`);
        break;
      case 'trip-edit':
        shell(plan(state.trip));
        break;
      case 'trip-cancel':
        confirmAction(
          'Cancel this trip?',
          'This preserves the trip history and locks further edits. This action cannot be undone.',
          'trip-status',
          { id, status: 'CANCELLED' },
          'Cancel trip',
        );
        break;
      case 'trip-complete':
        confirmAction(
          'Complete this trip?',
          'This locks the itinerary and unlocks memories and feedback. Complete only after the trip ends.',
          'trip-status',
          { id, status: 'COMPLETED' },
          'Complete trip',
        );
        break;
      case 'expense-new':
        childForm('expenses');
        break;
      case 'expense-edit':
        childForm(
          'expenses',
          state.trip.expenses.find((e) => e.id === id),
        );
        break;
      case 'itinerary-new':
        childForm('itinerary');
        break;
      case 'itinerary-edit':
        childForm(
          'itinerary',
          state.trip.itinerary.find((e) => e.id === id),
        );
        break;
      case 'packing-new':
        childForm('packing');
        break;
      case 'packing-edit':
        childForm(
          'packing',
          state.trip.packing.find((e) => e.id === id),
        );
        break;
      case 'expense-delete':
      case 'itinerary-delete':
      case 'packing-delete': {
        const type = action.split('-')[0] === 'expense' ? 'expenses' : action.split('-')[0];
        confirmAction(
          'Remove this item?',
          'This item will be removed from your trip.',
          type,
          { id, trip_id: state.trip.id, action: 'delete' },
          'Remove item',
        );
        break;
      }
      case 'memory-new':
        openDialog(
          'A moment to remember',
          `<form data-form="memory" class="form-grid">${select(
            'trip_id',
            'Completed trip',
            state.data.trips.filter((t) => t.status === 'COMPLETED').map((t) => [t.id, t.title]),
          )}${input('memory_date', 'Date', localDate(), 'date', 'required')}${input('title', 'Memory title', '', 'text', 'required maxlength="150"')}${input('photo_file', 'Photograph (JPEG / PNG, up to 3 MB)', '', 'file', 'accept="image/jpeg,image/png"')}${area('note', 'Your story')}${submit('Save memory')}</form>`,
        );
        syncMemoryDate(modal.querySelector('form'));
        break;
      case 'memory-delete':
        confirmAction(
          'Delete this memory?',
          'The note and photograph will be permanently deleted.',
          'memory',
          { id, action: 'delete' },
          'Delete memory',
        );
        break;
      case 'destination-new':
        destinationForm();
        break;
      case 'destination-edit':
        destinationForm(state.admin.destinations.find((d) => d.id === id));
        break;
      case 'user-toggle': {
        const u = state.admin.users.find((u) => u.id === id);
        confirmAction(
          u.active ? 'Disable this account?' : 'Enable this account?',
          `Change account access for ${u.name}.`,
          'admin/user',
          { id, active: !u.active },
          u.active ? 'Disable account' : 'Enable account',
        );
        break;
      }
      case 'feedback-reply': {
        const f = state.admin.feedback.find((f) => f.id === id);
        openDialog(
          'Respond to feedback',
          `<p>${escapeHtml(f.message)}</p><form data-form="admin-feedback"><input type="hidden" name="id" value="${id}">${area('response', 'Your response', f.response)}<div class="mt-3">${submit('Send response')}</div></form>`,
        );
        break;
      }
    }
  } catch (e) {
    toast(e.message, true);
  }
});
document.addEventListener('change', async (event) => {
  const el = event.target;
  if (el.name === 'trip_id' && el.form?.dataset.form === 'memory') {
    syncMemoryDate(el.form);
    return;
  }
  if (!el.matches('[data-pack]')) return;
  el.disabled = true;
  try {
    await mutation(
      'packing',
      { id: Number(el.dataset.pack), trip_id: state.trip.id, action: 'toggle', packed: el.checked },
      'Packing list updated.',
    );
  } catch (e) {
    el.checked = !el.checked;
    el.disabled = false;
    toast(e.message, true);
  }
});
document.addEventListener('submit', async (event) => {
  const form = event.target;
  if (!form.dataset.form) return;
  event.preventDefault();
  const type = form.dataset.form,
    body = Object.fromEntries(new FormData(form));
  const btn = form.querySelector('button[type="submit"],button:not([type])');
  if (btn?.disabled) return;
  if (btn) btn.disabled = true;
  form.querySelector('.error-message')?.remove();
  try {
    if (await packageSubmit(type, body, form)) return;
    if (type === 'login' || type === 'register') {
      roamCache.clearUserData();
      await api(type, body);
      location.hash = 'home';
      await start();
      return;
    }
    if (type === 'filter' || type === 'search') {
      location.hash = 'explore?' + new URLSearchParams(body);
      await render();
      return;
    }
    if (type === 'perfect') {
      state.perfectQuery = body;
      document.querySelector('#matches').innerHTML = matching(body);
      return;
    }
    if (type === 'admin-search') {
      location.hash = 'admin?' + new URLSearchParams(body);
      await render();
      return;
    }
    if (type === 'compare') {
      const id = Number(body.destination_id);
      if (!findDestination(id)) throw Error('Select a destination.');
      if (state.compare.has(id)) throw Error('This destination is already in the comparison list.');
      if (state.compare.size >= 3)
        throw Error('Compare up to three destinations at a time. Remove one first.');
      state.compare.add(id);
      saveCompareState();
      await render();
      toast('Destination added to comparison.');
      return;
    }
    if (type === 'trip') {
      const result = await api('trip', body);
      roamCache.remove('roam_trip_' + result.id);
      await refresh();
      location.hash = 'itinerary/' + result.id;
      await render();
      toast('Your trip is saved.');
      return;
    }
    if (type === 'profile') {
      await api('profile', body);
      const session = await api('session');
      state.user = session.user;
      roamCache.set('roam_session_cache', session);
      await render();
      toast('Profile updated.');
      return;
    }
    if (type === 'password') {
      await api('password', body);
      roamCache.clearUserData();
      await start();
      toast('Password changed. Sign in with your new password.');
      return;
    }
    if (type === 'memory' || type === 'admin-destination') {
      const file = body.photo_file;
      delete body.photo_file;
      if (file?.size) {
        if (file.size > 3_000_000) throw Error('Choose a photo smaller than 3 MB.');
        if (!['image/jpeg', 'image/png'].includes(file.type))
          throw Error('Choose a JPEG or PNG photograph.');
        body.photo = await new Promise((resolve, reject) => {
          const r = new FileReader();
          r.onload = () => resolve(r.result);
          r.onerror = () => reject(Error('Could not read this file.'));
          r.readAsDataURL(file);
        });
      }
      if (type === 'admin-destination') body.active = body.active === 'true';
      await mutation(
        type === 'memory' ? 'memory' : 'admin/destination',
        body,
        type === 'memory' ? 'Memory saved.' : 'Destination saved.',
      );
      return;
    }
    if (type === 'confirmed') {
      await mutation(form.dataset.path, JSON.parse(body.payload), 'Updated.');
      return;
    }
    if (type === 'admin-destination') body.active = body.active === 'true';
    await mutation(
      type.replace('admin-', 'admin/'),
      body,
      type === 'feedback' ? 'Thank you for sharing your experience.' : 'Saved.',
    );
  } catch (e) {
    const p = document.createElement('p');
    p.className = 'error-message';
    p.setAttribute('role', 'alert');
    p.textContent = e.message;
    form.prepend(p);
    toast(e.message, true);
  } finally {
    if (btn) btn.disabled = false;
  }
});
start();
