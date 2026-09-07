# Roam — Trip Planner

A Java MVC travel-planning application implementing the 15-menu client blueprint, plus an administrator workspace. Built with Java 17, Jakarta Servlets 6, JSP, JDBC/MySQL, Bootstrap 5.3.8, HTML, CSS and vanilla JavaScript. No Spring, React or Node server is required.

## Start locally

> 💡 **Step-by-step guide**: See [RUN_GUIDE.md](RUN_GUIDE.md) for full instructions, options, and troubleshooting.


On this Windows computer, double-click `start-local.cmd`. It prompts privately for an initial administrator email/password, then serves http://127.0.0.1:8080. Leave the email blank if you only want client access. Existing administrator passwords are never reset by startup. Maven and its dependency cache are available in the local `tools` folder; a source-only checkout requires Maven 3.9+ installed separately. Stop with Ctrl+C. Saved preview data is outside the build directory in `.local-data` and survives `mvn clean`.


Install JDK 17 or newer and Maven 3.9+. From this folder:

```sh
mvn verify
mvn test-compile exec:java -Dexec.mainClass=com.tripplanner.DevServer -Dexec.classpathScope=test
```

Open http://127.0.0.1:8080. Register an account. This local-only preview uses a persistent H2 database at `.local-data/dev-db`, separate from MySQL. H2 and embedded Tomcat are test dependencies and are excluded from the production WAR. Stop with Ctrl+C.

To create an administrator in the preview, set `ADMIN_EMAIL` and `ADMIN_PASSWORD` in the environment before starting. There are **no built-in production credentials**. The first administrator is provisioned only when none exists. Use a unique password of at least 12 characters. Environment bootstrap values do not reset an existing administrator’s password.

## Run with MySQL and Tomcat

1. Create a MySQL 8 database and service account using `deploy/mysql-setup.sql`, with your own strong password.
2. Set `DB_URL`, `DB_USER`, `DB_PASSWORD`, `ADMIN_EMAIL`, and `ADMIN_PASSWORD` in the Tomcat service environment. Example URL: `jdbc:mysql://localhost:3306/trip_planner?connectionTimeZone=UTC`. For remote MySQL use `sslMode=VERIFY_IDENTITY` with the appropriate trusted CA.
3. Run `mvn clean verify`; deploy `target/trip-planner.war` to Tomcat 10.1’s `webapps` folder.
4. Open `/trip-planner/`. To serve at `/`, rename the WAR to `ROOT.war`.
5. Sign in as the provisioned administrator and review the sample destination catalog, support email, dashboard message and registration settings.

Alternatively, copy `.env.example` to `.env`, replace all secrets, and run `docker compose up --build -d`. This binds HTTP to localhost only. The container build runs the integration suite. The MySQL volume retains data across restarts. **Do not use `docker compose down -v` unless you intend to destroy all data.**

## Features

- Client: dashboard, seasonal discovery, searchable/filterable destinations, preference matching, trip creation/editing, six-category budgets, daily itineraries, cancellation/completion, seasonal packing, 2–3 destination comparisons, wishlists, private photo memories, countdown, feedback, profile/password editing and confirmed logout.
- Admin: catalog creation/editing/publication, season/category/cost/activity curation, registration switch, dashboard message, support contact, user enable/disable, feedback responses, aggregate metrics and audit trail.
- Improvements: responsive layout, keyboard labels, reduced-motion support, empty/error/success states, duplicate-submit protection, printable itineraries, transactional trip generation, private photo normalization, and explicit planning estimates.

See `docs/FEATURES.md` for the PDF coverage matrix, `docs/ARCHITECTURE.md` for the design, `docs/OPERATIONS.md` for deployment and backup instructions, and `docs/VALIDATION.md` for verification results and limits.

## Important scope

This is a travel **planner**, not a booking engine. No payments, hotel/flight booking, live prices, maps, email delivery, weather service, or password-reset email are claimed. These are not in the supplied blueprint and require provider configuration and additional workflows if desired. All seeded rates and recommendations are sample editorial content, not current quotations. Cover images are illustrative travel photographs.

Production use requires your hosting environment, domain/TLS, secret management, backup/restore validation, destination content review, and capacity testing for your expected traffic. No software can be honestly certified to have zero bugs or unlimited performance.

## Tests

`mvn verify` runs HTTP integration tests on embedded Tomcat and H2. To repeat the same suite on an **empty disposable MySQL database**, set these Maven properties:

```sh
mvn test -DTEST_DB_URL=jdbc:mysql://127.0.0.1:3308/trip_planner_test -DTEST_DB_USER=test_user -DTEST_DB_PASSWORD=your_test_password
```

The suite creates test accounts and records. Never point it at production. Use a fresh database for each run. See `docs/VALIDATION.md` for what was actually executed.

## Free hosted MySQL and Vercel

See [the setup guide](docs/FREE-MYSQL-VERCEL.md) for Aiven Free, database initialization, Vercel container deployment and the 12-user capacity checks. A purchased domain is not required.



Latest local audit: [Local verification](docs/LOCAL-VERIFICATION.md), including the 16-scenario suites, UI regressions and launcher instructions.



## Package management and demo payments

Administrators now use a separate operations dashboard. Manage packages, hotels, transportation, booking requests and simulated payment histories. Clients can browse published packages and review their own bookings. See [Packages guide](docs/PACKAGES.md). Existing databases migrate to schema version 3 with initialization enabled.
