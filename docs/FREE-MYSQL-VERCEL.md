# Free MySQL and Vercel setup

Checked 6 September 2026. Start with [Aiven free MySQL](https://aiven.io/free-mysql-database). Its free plan currently needs no credit card and includes 1 GB disk, 1 GB RAM and 76 database connections. It has no availability SLA and inactive services can be stopped. This is a reasonable starting point for a small pilot with about 12 simultaneous users; production reliability and remote performance still need testing. Photos share the 1 GB database allowance. Monitor storage and export backups.

## 1. Create the database

1. Sign up at https://console.aiven.io/signup and verify your email.
2. Create a **MySQL** service and explicitly select the **Free / $0** plan, rather than a paid trial. Wait until it is running.
3. Open its Overview page. Keep the host, port, username and password privately in your password manager. Do not put them in Git or chat.
4. Under Connect → Databases create `trip_planner`. Alternatively use the supplied `defaultdb` and substitute that name below.
5. Connect with MySQL Workbench using the host, port and credentials. Select TLS certificate and hostname verification; supply the service CA certificate if required by the service. Use an up-to-date JDK for the application. Never fix certificate failures by disabling verification.

## 2. Initialize tables and the first administrator

Install JDK 17+ and Maven 3.9+. In PowerShell, from the project folder, set the following environment variables. Password prompts keep secrets out of the command itself:

```powershell
$env:DB_URL='jdbc:mysql://YOUR_HOST:YOUR_PORT/trip_planner?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC'
$env:DB_USER='avnadmin'
$env:DB_PASSWORD=[System.Net.NetworkCredential]::new('', (Read-Host 'Aiven database password' -AsSecureString)).Password
$env:ADMIN_EMAIL=Read-Host 'Initial administrator email'
$env:ADMIN_PASSWORD=[System.Net.NetworkCredential]::new('', (Read-Host 'New administrator password (12+ characters)' -AsSecureString)).Password
$env:DB_INITIALIZE='true'
mvn -B compile exec:java '-Dexec.mainClass=com.tripplanner.SchemaInitializer'
```

Continue only after the command reports schema version 3 initialized. This creates tables, sample destinations and the administrator. Do not run the integration tests against this database: tests create disposable fixtures. Remove the administrator bootstrap password from your shell afterwards:

```powershell
Remove-Item Env:ADMIN_PASSWORD
$env:DB_INITIALIZE='false'
```

For normal hosting use a separate MySQL user with SELECT, INSERT, UPDATE and DELETE permissions on `trip_planner.*`; retain the administrative credentials only for migrations and backups. Create that user and grant these permissions through your administrative Workbench connection. Use the new user's credentials in Vercel.

## 3. Deploy Java on Vercel

Vercel currently documents custom container images as a beta feature on all plans. This repository supplies `Dockerfile.vercel`, which builds the Java WAR and runs Tomcat. Use Vercel's container-image workflow; an ordinary static-site deployment will not execute Servlets/JSP. The documented workflow requires Docker locally. Container availability and deployment have not been verified on your account.

Follow https://vercel.com/docs/functions/container-images to link this project and build/deploy its container. Set the project root to this folder. Set these **server-side** environment variables before deployment:

| Variable      | Value                                    |
| ------------- | ---------------------------------------- |
| DB_URL        | The verified-TLS JDBC URL above          |
| DB_USER       | Restricted application database username |
| DB_PASSWORD   | That user's private password             |
| DB_INITIALIZE | `false`                                  |
| DB_POOL_SIZE  | `5`                                      |
| APP_HTTPS     | `true`                                   |
| PORT          | `8080`                                   |

No custom domain is needed: use the deployment's assigned HTTPS `vercel.app` address. Do not expose credentials as frontend variables. Isolate Preview deployments from the production database.

Sessions are stored in MySQL so server restarts and multiple containers can share logins. Each container opens at most five database connections; keep aggregate connections below the provider limit. Configure edge rate limits, and test legitimate 12-user traffic through Vercel because proxy addressing may group users under one per-instance IP limit. The container writes transient Tomcat files under `/tmp`; persistent data and photos reside in MySQL. Uploads are capped at 3 MB to leave room below Vercel's request-size limit.

## 4. Verify before inviting users

Check `/api/health`, register/login, create and edit a trip, upload a small completed-trip photo, and sign in as admin to manage destinations and users. Confirm login survives container restarts, logout revokes it, TLS verification succeeds, and no credentials appear in responses/logs. Repeat the 12-user workload against staging, including cold starts. Restore a backup into a separate database. Review sample destination prices and the support contact.

## Sources

- https://aiven.io/docs/products/mysql/concepts/mysql-free-tier
- https://aiven.io/docs/products/mysql/howto/connect-from-mysql-workbench
- https://aiven.io/docs/platform/concepts/tls-ssl-certificates
- https://dev.mysql.com/doc/connector-j/en/connector-j-server-authentication.html
- https://vercel.com/docs/functions/container-images
- https://vercel.com/docs/functions/limitations

Account creation, hosted database connection and live deployment remain to be completed using your accounts. Local passing tests are not a claim of tested hosting or guaranteed uptime.
