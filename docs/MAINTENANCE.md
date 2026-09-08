# Maintenance notes

Roam uses Java 17, Servlet/JSP controllers and services, JDBC persistence, and browser JavaScript. Keep changes within those boundaries unless a feature needs a different design.

## Source layout

- `ApiController`: request parsing, authentication, authorization and response handling.
- `TripService`: personal trip validation and related itinerary, packing and expense operations.
- `PackageService`: package catalog, supplier details, booking snapshots and demo payment entries.
- `Database` and `DatabaseSession`: JDBC access, schema initialization and shared sessions.
- `Security` and `RequestGuard`: password/token handling and request limits.
- `app.js`: public landing page, login and traveler views.
- `packages.js`: package views and administration navigation.
- `app.css`: application styles, including responsive layouts and reduced-motion rules.

## Conventions

Use descriptive names for request bodies, connections, result sets and view helpers. Keep comments focused on constraints or decisions that are not apparent from the code. Describe actual behavior in documentation; avoid status claims that become stale as soon as the server stops.

`.editorconfig` defines indentation and line endings. `.prettierrc.json` supplies formatting options for JavaScript, CSS, XML, Markdown and Java. This pass used Prettier 3.9.6, prettier-plugin-java 2.10.3, @prettier/plugin-xml 3.4.2 and sql-formatter 15.8.2. These are development tools, not server dependencies. SQL uses the MySQL dialect. JSP is formatted manually to keep directives and expressions intact.

Do not reformat vendored Bootstrap, rewrite image files or certificates, or edit generated WAR/ZIP files directly. Build artifacts belong in `target` and `release`; local data and credentials must stay outside the source archive.

## September 2026 readability pass

Expanded compressed Java, JavaScript, CSS, SQL and configuration files; renamed terse database/request variables and browser helpers; named password-hashing constants; and updated startup, architecture and package documentation. The password encoding and verification algorithm remain compatible with existing accounts. The schema initializer's message now reports the current version, 3.

The MySQL test run exposed shared fixture state: the custom package test left its newly created destination published for the next test class. Its cleanup now hides that fixture and removes its package from the test catalog. No application data cleanup runs as part of normal startup.

Verification includes Java integration suites on H2 and a disposable MySQL database, JavaScript regressions, PowerShell syntax validation, and before/after JavaScript syntax-tree comparison. The browser syntax trees match once the intended helper renames and formatting metadata are accounted for. Existing image assets, Bootstrap and the CA certificate were retained byte for byte.

For changes that affect behavior, run `mvn verify` and `node --test src/test/js/ui.test.cjs`. Use a fresh disposable schema for a MySQL suite. Account, package, photo-upload, itinerary, payment and ownership workflows are covered by integration tests; do not point those tests at the application's working database.

### Verification results

- H2: 19 integration tests passed.
- MySQL 8.0.46, fresh disposable schema: 19 integration tests passed after fixture cleanup.
- Browser JavaScript: eight regression tests passed; both production scripts matched their earlier syntax trees after helper-name normalization.
- PowerShell: syntax check passed and executable tokens matched the earlier launcher.
- MySQL mixed workload: 12 users, 108 requests, no failures; local p95 52 ms.
- Source archive and WAR contents were checked against the current files.

The existing local server could not be stopped from this session because Windows denied process access. Restart it from its terminal to load the updated Java classes. No application database reset is needed.
