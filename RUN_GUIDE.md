# Roam — Trip Planner: How to Run the Project

This guide provides step-by-step instructions for running the **Roam — Trip Planner** application on your machine.

---

## ⚡ Current Status

The application is **currently running** on your machine:
- **URL**: [http://127.0.0.1:8080](http://127.0.0.1:8080)
- **Status**: Active (Tomcat DevServer running on port 8080)
- **Local Database**: H2 embedded database stored at `.local-data/dev-db`

You can open [http://127.0.0.1:8080](http://127.0.0.1:8080) in any web browser right now to start using the app!

---

## 📋 System Prerequisites

Before running the project on your own, ensure you have:

| Requirement | Details | Status on This Machine |
| :--- | :--- | :--- |
| **Java JDK** | JDK 17 or newer | ✅ **Installed** (`Java 25.0.4.1`) |
| **Maven** | Apache Maven 3.9+ | ✅ **Pre-bundled** in `tools/apache-maven-3.9.11` |
| **Dependencies** | Offline Maven dependencies cache | ✅ **Pre-bundled** in `tools/m2` (no internet download required!) |
| **Port** | Port `8080` available | Used by current server instance |

---

## 🚀 Step-by-Step Instructions to Run the Project

Choose whichever method best suits your workflow:

### Method 1: 1-Click Windows Launcher (Easiest & Recommended)

This is the fastest and easiest way to start the server on Windows.

1. Open File Explorer and navigate to the project directory:
   ```
   d:\Github\TRIP-PLANNER
   ```
2. Double-click the file:
   ```
   start-local.cmd
   ```
3. A command prompt window will open with the setup prompt:
   - **Administrator email**:
     - To create an admin account: type your email address (e.g. `admin@example.com`) and press **Enter**, then enter a password (at least 12 characters).
     - To run without creating an admin account: simply press **Enter** (leave blank). You can register a regular user account directly on the web page.
4. The server will start and print:
   ```
   Starting http://127.0.0.1:8080. H2 data is in .local-data; MySQL mode uses its configured database. Stop with Ctrl+C.
   Trip Planner: http://127.0.0.1:8080 (local H2 development data)
   ```
5. Open your browser and go to:
   ```
   http://127.0.0.1:8080
   ```
6. **To Stop**: Click on the command prompt window and press `Ctrl + C`.

---

### Method 2: PowerShell Script (Fast / Non-Interactive)

If you prefer using PowerShell or want to launch without interactive prompts:

1. Open **PowerShell** in the project directory (`d:\Github\TRIP-PLANNER`).
2. Run the startup script with the `-SkipAdminSetup` flag:
   ```powershell
   powershell.exe -ExecutionPolicy Bypass -File .\start-local.ps1 -SkipAdminSetup
   ```
3. *(Optional)* If you want to configure an administrator account automatically without being prompted:
   ```powershell
   $env:ADMIN_EMAIL = "admin@example.com"
   $env:ADMIN_PASSWORD = "AdminPassword123!"
   powershell.exe -ExecutionPolicy Bypass -File .\start-local.ps1 -SkipAdminSetup
   ```
4. Open [http://127.0.0.1:8080](http://127.0.0.1:8080).

---

### Method 3: Run via Bundled Maven (Terminal / Command Line)

If you want to run the underlying Maven command directly:

1. Open PowerShell or Command Prompt in `d:\Github\TRIP-PLANNER`.
2. Run the following command (utilizing the bundled Maven and local dependency cache):
   ```powershell
   .\tools\apache-maven-3.9.11\bin\mvn.cmd -B "-Dmaven.repo.local=tools/m2" test-compile exec:java "-Dexec.mainClass=com.tripplanner.DevServer" "-Dexec.classpathScope=test"
   ```
   *(Note: If you have Maven installed system-wide on your PATH, you can also run `mvn test-compile exec:java -Dexec.mainClass=com.tripplanner.DevServer -Dexec.classpathScope=test`)*.
3. Open [http://127.0.0.1:8080](http://127.0.0.1:8080).

---

### Method 4: Run with MySQL Database (Production or Local Service)

By default, the app uses an embedded H2 database (in MySQL mode) stored in `.local-data/`. If you want to connect to a real MySQL 8+ database:

1. Create a MySQL database and user using the schema provided in `deploy/mysql-setup.sql`.
2. Run the script with the `-UseMySql` flag:
   ```powershell
   powershell.exe -ExecutionPolicy Bypass -File .\start-local.ps1 -UseMySql
   ```
3. Enter your MySQL JDBC URL, username, and password when prompted (e.g. `jdbc:mysql://localhost:3306/trip_planner?connectionTimeZone=UTC`).
4. The application will initialize the tables and seed default destinations automatically.

---

### Method 5: Run with Docker Compose

If you have Docker Desktop installed:

1. Copy `.env.example` to `.env`:
   ```powershell
   Copy-Item .env.example .env
   ```
2. Update the credentials in `.env` if desired.
3. Build and launch the containers:
   ```bash
   docker compose up --build -d
   ```
4. Open [http://localhost:8080](http://localhost:8080).
5. To stop:
   ```bash
   docker compose down
   ```
   *(Note: Do not pass `-v` unless you want to delete the database volume).*

---

## 🧭 Using the Web Application

Once the server is running:

1. **Open the browser**: Navigate to `http://127.0.0.1:8080`.
2. **Register an Account**:
   - On the right side of the landing page, click **"Create an account"**.
   - Enter your **Full Name**, **Email Address**, and a **Password** (min. 12 characters).
   - Click **"Create account"**.
3. **Explore Features**:
   - **Discover**:
     - *Dashboard*: Overview of your trips, saved destinations, and planned expenses.
     - *Explore by season*: Filter by Summer, Winter, Monsoon, Autumn.
     - *Explore destinations*: View cards for Goa, Jaipur, Manali, Munnar, Udaipur, Rishikesh.
     - *Find my perfect trip*: Interactive quiz matching season, trip type, and budget.
   - **Make it happen**:
     - *Plan my trip*: Create customized trips with dates, travelers, and estimated budget.
     - *Budget planner*: Manage 6 expense categories (Stay, Food, Transport, Sightseeing, Shopping, Misc).
     - *My itinerary*: Day-by-day scheduling with morning, afternoon, and evening time slots.
     - *Packing checklist*: Automatic packing list customized by destination and season.
     - *Compare destinations*: Side-by-side comparison of 2–3 destinations.
     - *Wishlist*: Save destinations you want to visit later.
   - **Your journey**:
     - *Travel memories*: Upload travel photos and notes.
     - *Trip countdown*: Live countdown timer to upcoming adventures.
     - *Feedback*: Submit review ratings and suggestions.

---

## 🛠️ Management & Troubleshooting

### How to Check if the Server is Already Running
In PowerShell:
```powershell
Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
```
Or test the health check endpoint:
```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/health"
```

### Port 8080 is Already in Use ("Address already in use")
If you see an error indicating port 8080 is occupied, you can find and stop the existing process:
```powershell
$procId = (Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue).OwningProcess
if ($procId) { Stop-Process -Id $procId -Force }
```
Then start the application again using `start-local.cmd` or Method 1/2.

### Where is the Local Data Stored?
- Development data is saved in `.local-data/dev-db.mv.db`.
- It persists across restarts and survives `mvn clean`.
- If you want to reset everything back to a clean state:
  1. Stop the server (`Ctrl + C`).
  2. Delete the `.local-data` folder:
     ```powershell
     Remove-Item -Recurse -Force .\.local-data
     ```
  3. Start the server again; it will re-seed default destinations and settings.

### Java Version Requirement
The project requires Java 17 or higher. To verify your Java version:
```powershell
java -version
```
Ensure your `JAVA_HOME` environment variable points to a valid JDK 17+ installation if you encounter any JDK errors.
