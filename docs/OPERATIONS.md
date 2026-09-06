# Deployment and operations

## Release procedure

1. Build and test with `mvn clean verify` on JDK 17+. Archive the WAR and this source revision together.
2. Back up the existing database before a release. Bootstrap schema version 2 once on a single application instance. `DB_INITIALIZE=false` skips bootstrap after schema creation and requires version 2 to exist.
3. Store credentials outside source control. Provision a restricted MySQL account. Remove bootstrap `ADMIN_PASSWORD` from the runtime after initial admin creation.
4. Deploy the WAR to Tomcat 10.1; use the supplied server settings or equivalent. Avoid exposing Tomcat Manager or the database port publicly.
5. Configure a provider hostname or custom domain and valid TLS certificate at nginx or an equivalent trusted reverse proxy. Use the supplied nginx example. HTTP is only for localhost development.
6. Verify `GET /api/health` (or `/trip-planner/api/health` under that context), registration/login, a test trip, admin controls, secure session cookies and backup restoration in staging.
7. Review and replace the sample catalog, costs, images, support email and operator policies before allowing real client accounts.

The Docker Compose example is for a single-host deployment behind a host-level reverse proxy. It binds the app only to 127.0.0.1 and does not expose MySQL. Internal Docker database connections use the private network; use verified MySQL TLS for remote/multi-host databases. Pin reviewed image digests in your release infrastructure.

## Backups

Use your platform’s secret store or MySQL login path so passwords are not put into shell history. Example with an already configured `backup` login path:

```sh
mysqldump --login-path=backup --single-transaction --hex-blob --routines --triggers trip_planner > trip-planner-backup.sql
```

Photo bytes live in MySQL and are included in the backup. Encrypt backups at rest; retain daily backups and a pre-release snapshot according to your organization’s retention policy. Restore a backup into a separate database and verify account, trip and photo counts. A backup is not proven until restoration succeeds.

## Monitoring

- Monitor `/api/health` status/latency, HTTP 5xx/429 counts, application logs, MySQL storage, backup age, pool utilization and JVM memory.
- Investigate persistent 503 responses; they indicate a database issue or timeout. Do not suppress them or expose the exception to clients.
- RequestGuard is per application instance. Configure rate limits at the edge for distributed deployments.
- Set an expected concurrent-user target and response-time objective, then load-test with representative trips and photo volume in staging. Local smoke timings do not prove internet-scale capacity.
- Rotate database secrets using your service platform and restart the app. Change admin passwords through Profile; sessions are revoked automatically.
- Rebuild regularly with reviewed dependency/security updates. Production approval requires vulnerability scanning in your environment.

## Recovery and rollback

For application-only rollback, restore the previous WAR, restart Tomcat and run the smoke checks. Version 2 adds shared database sessions; initialize it once using SchemaInitializer before deploying with DB_INITIALIZE=false. Future migrations need explicit up/down compatibility plans. Never delete a production volume as a reset shortcut.

If the first database bootstrap is interrupted, restart the single bootstrap instance; existing tables and the trip index are handled idempotently. If data seeds were partially loaded, inspect and reconcile the catalog in Admin. For administrator lockout, the database operator must use a controlled recovery procedure; no anonymous reset or default bypass exists.

## Runtime configuration

| Variable | Purpose |
|---|---|
| DB_URL | JDBC MySQL URL; default localhost database |
| DB_USER | Service account; default trip_planner |
| DB_PASSWORD | Database password; no usable production default |
| DB_INITIALIZE | `true` initially; `false` after controlled bootstrap |
| ADMIN_EMAIL | Initial administrator email, only if no admin exists |
| ADMIN_PASSWORD | Initial administrator password, minimum 12 characters |
| JAVA_OPTS | Heap sizing and JVM options in Tomcat deployment |
| TZ | Server business-date time zone; keep consistent across app and DB |

Use server-side UTC timestamps for operations where possible. Trip dates are calendar dates; countdown uses the traveler’s device time zone, as disclosed in the view.

