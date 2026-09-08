# Local functionality verification — 7 September 2026

This report records an earlier verification pass. See [Packages](PACKAGES.md) for the package workflow and [Maintenance](MAINTENANCE.md) for the readability refactor.

Deployment is deferred at the user's request. This pass focused on working client/admin behavior and local startup.

## Fixed and tested

- Memory dates default within the selected completed trip, expose its date limits, preserve a valid date, and adjust when another trip is selected.
- Admin catalog lists require at least one nonblank entry. Legacy empty attraction lists fall back to the destination name during itinerary generation.

- Packing edits update the same record, retain its packed status and do not duplicate it. Unknown actions are rejected without inserting an item.
- Existing trips retain an unpublished destination while being edited; unpublished destinations cannot be chosen for new trips.
- Zero trip budgets and zero expenses remain zero in edit forms.
- Password verification preserves whitespace instead of silently trimming it.
- Damaged image uploads return a validation error and do not create a memory record.
- Invalid support email settings are rejected without partially saving the announcement or registration switch.
- Expired sessions obtain a fresh CSRF token, close private dialogs and clear the previous user's client state before sign-in.
- Writes for the same user are serialized to prevent duplicate wishlist saves and racing quota checks. Concurrent wishlist saves both succeed with exactly one saved row.
- Local preview data now lives in `.local-data`, outside Maven's disposable `target` directory.

## Evidence

| Check                               | Result                                                                                                                                                                                                                                                           |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| H2 HTTP integration suite           | 17 tests, zero failures/errors                                                                                                                                                                                                                                   |
| MySQL 8.0.46 HTTP integration suite | 16 tests, zero failures/errors on a disposable local schema                                                                                                                                                                                                      |
| Additional MySQL catalog regression | Passed separately on a fresh disposable database: empty list validation and legacy itinerary fallback                                                                                                                                                            |
| JavaScript regression suite         | 4 tests, zero failures                                                                                                                                                                                                                                           |
| JavaScript syntax                   | Passed                                                                                                                                                                                                                                                           |
| PowerShell launcher parsing         | Passed                                                                                                                                                                                                                                                           |
| Local launcher                      | Started Tomcat successfully on 127.0.0.1:8080                                                                                                                                                                                                                    |
| MySQL startup mode                  | Started against the disposable local MySQL schema with initialization disabled; HTTP health check returned ok                                                                                                                                                    |
| Browser                             | Login, trip creation, zero-budget display/editing, packing edit dialog/save and unchanged packing count verified                                                                                                                                                 |
| Seasonal page                       | All four keyboard-focused icons reported their intended animation names; layout and visible focus outline inspected in-browser. The loaded reduced-motion stylesheet contains an important global animation override; OS preference switching was not exercised. |
| Mixed MySQL workload                | 12 concurrent users, 108 requests, zero failures; local warm-server p95 90 ms, maximum 104 ms                                                                                                                                                                    |

The Java suite also covers client ownership, CSRF, trip status/dates, expenses, generated itinerary/packing, photo privacy, feedback uniqueness/replies, admin publication/settings/access, pagination, password revocation, shared sessions, logout and login throttling.

Run `mvn verify` for Java tests and `node --test src/test/js/ui.test.cjs` for UI regression tests. MySQL tests must use a new disposable database and the `TEST_DB_*` Maven properties described in README; never point the test suite at the hosted application database.

## Start and use

Double-click `start-local.cmd` in the project folder. Choose an administrator email and password at its private prompts to enable admin access on first startup; registration creates client accounts. Existing administrators are not reset. Stop a currently running preview before starting another on port 8080.

The default preview uses H2 for self-contained local use. To run against MySQL, use `powershell -NoProfile -ExecutionPolicy Bypass -File .\start-local.ps1 -UseMySql` from the project folder. The launcher asks for the JDBC URL, database user and a hidden password, unless supplied through `DB_URL`, `DB_USER` and `DB_PASSWORD` environment variables. Create an empty application database first; startup initializes its tables. Set `DB_INITIALIZE=false` to use an already initialized schema without running initialization. Administrator creation requires initialization to be enabled. Never use the integration test commands against your application database.

For local MySQL, a URL can be `jdbc:mysql://127.0.0.1:3306/trip_planner?connectionTimeZone=UTC`. For Aiven, follow `HOSTED-DATABASE.md` for certificate verification and the JVM truststore settings; the MySQL launcher does not automatically install a CA certificate. Database passwords are not written to project files. Local H2 and MySQL data are separate. The local `tools` directory contains Maven and cached dependencies on this computer; it is excluded from Git and the source-only ZIP.

The completed checks support the specific behaviors above. They are not a guarantee that every possible input, browser, concurrency schedule or operating condition is defect-free. Deployment, external-service setup and hosted performance were intentionally outside this pass.
