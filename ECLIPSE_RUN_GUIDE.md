# How to Open and Run Trip Planner in Eclipse IDE

This guide is prepared for your presentation to easily run the project on `localhost:8080` using Eclipse IDE.

---

## Step 1: Open Eclipse IDE
Open Eclipse from your Start Menu or from:
`C:\Users\riya0\eclipse\jee-2026-06\eclipse\eclipse.exe`

Select your usual workspace (e.g. `C:\Users\riya0\eclipse-workspace`) and click **Launch**.

---

## Step 2: Import the Project (3 Clicks)
1. In Eclipse, click **File** (top-left menu) $\rightarrow$ **Import...**
2. In the import window, expand **General** $\rightarrow$ select **Existing Projects into Workspace** (or **Maven** $\rightarrow$ **Existing Maven Projects**).
3. Click **Next**.
4. Next to **Select root directory**, click **Browse...** and select:
   `D:\Github\TRIP-PLANNER`
5. Ensure `trip-planner` is checked.
6. Click **Finish**.

Eclipse will import the project with zero errors.

---

## Step 3: Run on Localhost in Front of the Teacher (1 Click)

You have two very easy ways to run the project in Eclipse:

### Method A (One Click with the Play Button):
1. In Eclipse's top toolbar, click the dropdown arrow next to the **Green Run Play button (▶)**.
2. Select **`Run_Trip_Planner`**.

### Method B (Right-click in Package Explorer):
1. In the left **Package Explorer** / **Project Explorer** pane:
2. Expand `trip-planner` $\rightarrow$ expand `src/test/java` $\rightarrow$ expand `com.tripplanner`.
3. Right-click on **`DevServer.java`**.
4. Select **Run As** $\rightarrow$ **Java Application**.

---

## Step 4: Verify in Console and Browser

1. In Eclipse, look at the bottom **Console** tab. You will see Apache Tomcat boot up:
   ```text
   INFO: Starting service [Tomcat]
   INFO: Starting Servlet engine: [Apache Tomcat/10.1.59]
   INFO: Starting ProtocolHandler ["http-nio-127.0.0.1-8080"]
   Trip Planner web application successfully started.
   Trip Planner: http://127.0.0.1:8080 (local H2 development data)
   ```

2. Open your web browser (Chrome / Edge) and go to:
   **`http://localhost:8080`** (or `http://127.0.0.1:8080`)

3. The entire Roam Trip Planner project is running locally!

### Local Credentials:
- **Admin Email:** `admin@roam.local`
- **Admin Password:** `AdminPass1234!`
- **Client Account:** You can register any new account or use your existing client account.

---

## Step 5: How to Stop the Server in Eclipse
When the presentation or demo is finished:
- In Eclipse's **Console** tab (bottom pane), click the **Red Square button (⏹ Terminate)** on the right side of the console toolbar.

---

## Quick Viva / Teacher Q&A Cheat Sheet:

- **Q: Where is your REST API controller?**
  - **A:** `src/main/java/com/tripplanner/ApiController.java` — it handles all JSON endpoints (`/api/session`, `/api/data`, `/api/trip`, `/api/packages`).
- **Q: Where is your Database connection pool?**
  - **A:** `src/main/java/com/tripplanner/Database.java` — uses HikariCP connection pool with prepared statements to prevent SQL injection.
- **Q: Where is the Deployment Descriptor?**
  - **A:** `src/main/webapp/WEB-INF/web.xml` — configures `RequestGuard` filter, `AssetCacheFilter`, `AppListener`, and servlet mappings.
- **Q: Why do we run `DevServer.java` instead of Eclipse's external Tomcat server?**
  - **A:** Because this project uses **Jakarta EE 10 / Servlet 6.0**, which requires **Tomcat 10.1**. `DevServer.java` bundles embedded Tomcat 10.1.59 via Maven, ensuring zero server configuration and 100% reliable startup on localhost.
