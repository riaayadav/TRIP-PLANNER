# Running Roam locally

The project folder on this computer is `D:\Github\TRIP-PLANNER`. Start the server before opening [Roam](http://127.0.0.1:8080). The page is available only while the server is running.

## Requirements

- JDK 17 or newer, with `JAVA_HOME` and `PATH` configured.
- Maven 3.9 or newer. This computer has Maven and cached dependencies under `tools/`; those are not included in the source ZIP.
- Port 8080 available.

## Start on Windows

Double-click `start-local.cmd`. On first setup, enter an administrator email and a password of 12–128 characters. The password prompt hides your input. Leave the email blank to keep existing accounts and start without creating an administrator. Startup never resets an existing administrator's password.

For an existing setup, open PowerShell in the project folder and run:

```powershell
.\start-local.ps1 -SkipAdminSetup
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080). Choose **Sign in** on the landing page. New travelers can choose **Create an account** on the login page when registration is enabled.

Administrators enter the Operations overview. Travelers enter their personal dashboard. Package management and demo-payment instructions are in [Packages](docs/PACKAGES.md).

Stop the server with **Ctrl+C** in its terminal. Stop the old instance before starting another one.

## Choose a database

The default launcher uses H2 stored at `.local-data/dev-db.mv.db`. This data survives restarts and `mvn clean`. Keep a backup before moving or resetting it; deleting `.local-data` removes the local accounts, plans and memories.

To use MySQL instead:

```powershell
.\start-local.ps1 -UseMySql
```

The launcher asks for the JDBC URL, database user and password. It also accepts `DB_URL`, `DB_USER` and `DB_PASSWORD` from the environment. A local URL is `jdbc:mysql://127.0.0.1:3306/trip_planner?connectionTimeZone=UTC`. Create the database and service account first; `deploy/mysql-setup.sql` is a starting point.

The application initializes or migrates its tables at startup unless `DB_INITIALIZE=false`. The current schema version is 4. Existing version-2 databases need initialization enabled once to apply the package migration. Local H2, local MySQL and hosted MySQL are separate databases.

For Aiven certificate and truststore setup, see [Hosted database](docs/HOSTED-DATABASE.md). Keep credentials in the environment rather than source files.

## Run through Maven

With Maven on your PATH:

```powershell
mvn test-compile exec:java '-Dexec.mainClass=com.tripplanner.DevServer' '-Dexec.classpathScope=test'
```

The launcher is more convenient when using this computer's cached dependencies or setting up an administrator. H2 and embedded Tomcat are development/test dependencies; the WAR uses the external servlet container and MySQL.

## Docker

Copy `.env.example` to `.env` and replace its placeholder credentials, then run:

```sh
docker compose up --build -d
```

Stop with `docker compose down`. Adding `-v` also deletes the database volume. Deployment instructions and backup procedures are in [Operations](docs/OPERATIONS.md).

## Troubleshooting

Check whether the application is responding:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
```

An `ok` status means the application can reach its database. If startup reports that port 8080 is occupied, stop the existing Trip Planner terminal. Do not terminate an unrelated application simply because it uses the same port.

For a Java error, run `java -version` and check `JAVA_HOME`. For a database lock error, stop the other application instance before opening the same H2 file. For stale page assets after an update, reload the browser page.

## Verify changes

Run `mvn verify` and `node --test src/test/js/ui.test.cjs`. The integration tests create accounts and records; use only an empty disposable database when supplying `TEST_DB_URL`. See the [README](README.md) for the MySQL test command.
