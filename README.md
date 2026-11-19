# Roam — Trip Planner

A Java MVC travel planner with a personal traveler dashboard and a separate administration workspace. Built with Java 17, Jakarta Servlets 6, JSP, JDBC/MySQL, Bootstrap 5.3.8, HTML, CSS and vanilla JavaScript. No Spring, React or Node server is required.

## Start locally

> **Run instructions**: See [RUN_GUIDE.md](RUN_GUIDE.md) for full instructions, options, and troubleshooting.

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

- Client: dashboard, seasonal discovery, searchable/filterable destinations, preference matching, trip creation/editing, six-category budgets, daily itineraries, cancellation/completion, seasonal packing, 2–3 destination comparisons, wishlists, private photo memories, countdown, feedback, profile/password editing and confirmed logout; published package browsing, booking requests and personal payment history.
- Admin: a separate operations dashboard; package creation, editing, publication and removal; hotel and transport management; booking statuses and a simulated receipt/refund ledger; destination catalog creation/editing/publication, season/category/cost/activity curation, registration switch, dashboard message, support contact, user enable/disable, feedback responses, aggregate metrics and audit trail.
- Improvements: responsive layout, keyboard labels, reduced-motion support, empty/error/success states, duplicate-submit protection, printable itineraries, transactional trip generation, private photo normalization, and explicit planning estimates.

See `docs/FEATURES.md` for the feature coverage matrix, `docs/ARCHITECTURE.md` for architectural design, and `PROJECT_CODE_WALKTHROUGH.md` for code walkthrough.

## Scope & Project Notes

The application supports personal trip planning, destination discovery, budget tracking, and package booking requests. Payment entries are simulated for demonstration purposes. The package catalog includes hotel and transportation options, inclusions, exclusions, and itineraries.

## Tests

Run backend integration tests:
```sh
mvn test
```

Run frontend UI regression tests:
```sh
node --test src/test/js/ui.test.cjs
```

## Package Management & Demo Payments

Administrators have access to a dedicated operations dashboard to manage packages, hotels, transportation, booking requests, and simulated payment ledgers. Clients can browse published packages, submit booking requests, and review their bookings and receipts. See [Packages Guide](docs/PACKAGES.md).
