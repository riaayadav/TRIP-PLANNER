# Roam — Easy Code & Architecture Walkthrough

This guide explains the entire **Roam Trip Planner** codebase in plain, simple English. Whether you are presenting this project in a college viva, reviewing it with examiners, or onboarding as a developer, this document will help you understand every single part of the project effortlessly.

---

## 1. Quick Project Summary

| Item | Details |
| :--- | :--- |
| **Project Name** | **Roam — Personal Trip Planner & Package Booking Suite** |
| **Architecture** | **MVC (Model-View-Controller)** using Jakarta EE Servlets and JSP |
| **Backend Language** | **Java 17** (Standard Enterprise Java) |
| **Web Server** | **Jakarta Servlets 6.0** / Apache Tomcat 10.1 |
| **Database** | **MySQL 8** (Production) / **H2** (Local preview & unit testing) |
| **Connection Pool** | **HikariCP** (High performance JDBC connection pooling) |
| **Frontend** | **HTML5, Vanilla CSS, Vanilla JavaScript (ES6+), Bootstrap 5.3** |
| **Frameworks** | Pure Java Servlets & JDBC — **No heavy Spring, Hibernate, or React required** |

---

## 2. The MVC Architecture in Plain English

```
[ User Browser ]
       │
       ▼ (HTTP Request / JSON)
┌─────────────────────────────────────────────────────────────┐
│                       CONTROLLER                            │
│  • PageController: Serves the HTML shell & security headers │
│  • ApiController: Central REST API router for all endpoints │
│  • RequestGuard: Rate-limiting filter to prevent abuse      │
└──────────────────────────────┬──────────────────────────────┘
                               │
       ▼ (Method calls)        │
┌──────────────────────────────┴──────────────────────────────┐
│                         SERVICES                            │
│  • TripService: Business logic for trips, itinerary, packing│
│  • PackageService: Packages, hotels, transport, payments    │
│  • Security: PBKDF2 hashing, secure random tokens           │
│  • DatabaseSession: Session persistence & CSRF tokens       │
└──────────────────────────────┬──────────────────────────────┘
                               │
       ▼ (Prepared SQL)        │
┌──────────────────────────────┴──────────────────────────────┐
│                    DATABASE ACCESS (DAO)                    │
│  • Database: HikariCP connection pool, automated migrations │
│  • MySQL / H2 Relational Database                           │
└─────────────────────────────────────────────────────────────┘
```

1. **View (Presentation Layer)**:
   - [`src/main/webapp/WEB-INF/app.jsp`](file:///d:/Github/TRIP-PLANNER/src/main/webapp/WEB-INF/app.jsp): The single HTML page shell.
   - [`src/main/webapp/assets/app.js`](file:///d:/Github/TRIP-PLANNER/src/main/webapp/assets/app.js): Single Page Application (SPA) client router, interactive UI views, and form handlers.
   - [`src/main/webapp/assets/packages.js`](file:///d:/Github/TRIP-PLANNER/src/main/webapp/assets/packages.js): Travel package cards, hotel/transport management, and booking workflow.
   - [`src/main/webapp/assets/app.css`](file:///d:/Github/TRIP-PLANNER/src/main/webapp/assets/app.css): Clean typography, responsive card layouts, dark banners, and print styles.

2. **Controller (Traffic Control Layer)**:
   - [`PageController.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/PageController.java): Serves the JSP view and sets security headers (Content Security Policy, Cache-Control).
   - [`ApiController.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/ApiController.java): Central REST API servlet. Receives JSON from JavaScript, checks session and CSRF tokens, calls business services, and returns JSON.
   - [`RequestGuard.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/RequestGuard.java): A Servlet Filter that limits requests per IP (sliding window token bucket) to protect against DoS and brute-force attacks.

3. **Model & Business Logic Layer**:
   - [`TripService.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/TripService.java): Trip creation, smart day-by-day itinerary suggestions, season-tailored packing lists, and budget calculation.
   - [`PackageService.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/PackageService.java): Travel package publication, hotel & transport options, booking requests, and simulated double-entry payment ledger.
   - [`Security.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/Security.java): Cryptographic password hashing (PBKDF2 with salt) and constant-time equality checks.
   - [`DatabaseSession.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/DatabaseSession.java): Manages user login sessions stored directly in the database (`web_sessions`), automatically rotating tokens on login.

4. **Data Access Layer (DAO)**:
   - [`Database.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/Database.java): Manages HikariCP connection pooling, runs safe parameter-bound queries via JDBC `PreparedStatement`, and executes automated schema migrations from v1 to v4.

---

## 3. Directory Map & File Responsibilities

| File Path | What It Does (In 1 Simple Sentence) |
| :--- | :--- |
| [`SchemaInitializer.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/SchemaInitializer.java) | Command-line tool to run database schema migrations before starting the web server. |
| [`AppListener.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/AppListener.java) | Starts the database pool when Tomcat boots up and shuts it down when Tomcat stops. |
| [`PageController.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/PageController.java) | Serves the private `WEB-INF/app.jsp` template with HTTP security headers. |
| [`ApiController.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/ApiController.java) | Handles all JSON REST API requests, authentication, and admin actions. |
| [`Database.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/Database.java) | Connects to MySQL/H2 using HikariCP and executes parameterized SQL statements safely. |
| [`DatabaseSession.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/DatabaseSession.java) | Stores user sessions in the `web_sessions` table with 30-minute expiration. |
| [`TripService.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/TripService.java) | Validates user input, calculates trip duration, and generates suggested itineraries. |
| [`PackageService.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/PackageService.java) | Manages package listings, hotels, transportation, bookings, and demo payments. |
| [`Security.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/Security.java) | Hashes passwords with PBKDF2-HMAC-SHA256 (600,000 iterations) and 16-byte random salt. |
| [`RequestGuard.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/RequestGuard.java) | Rate-limiting filter that prevents bots from spamming login or API endpoints. |
| [`app.jsp`](file:///d:/Github/TRIP-PLANNER/src/main/webapp/WEB-INF/app.jsp) | Base HTML layout with viewport tags, stylesheets, and modal dialog containers. |
| [`web.xml`](file:///d:/Github/TRIP-PLANNER/src/main/webapp/WEB-INF/web.xml) | Deployment descriptor declaring servlets, URL mappings, and security filter chains. |
| [`app.js`](file:///d:/Github/TRIP-PLANNER/src/main/webapp/assets/app.js) | Frontend client routing, dashboard rendering, itinerary builder, and budget tracking. |
| [`packages.js`](file:///d:/Github/TRIP-PLANNER/src/main/webapp/assets/packages.js) | Frontend package booking modal, supplier forms, and simulated payment ledger UI. |

---

## 4. Key Workflows Explained Step-by-Step

### A. How User Authentication Works
1. **User enters email & password** on the login form.
2. The browser sends a `POST` request to `/api/login` with JSON payload and CSRF header.
3. [`RequestGuard.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/RequestGuard.java) checks if the IP has exceeded 50 attempts in 15 minutes.
4. [`ApiController.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/ApiController.java) fetches the user row by email from MySQL.
5. [`Security.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/Security.java) derives the PBKDF2 hash of the entered password using the stored salt and compares it using `MessageDigest.isEqual`.
6. [`DatabaseSession.java`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/DatabaseSession.java) creates a session row in `web_sessions`, rotates the cookie token, sets `HttpOnly` and `SameSite=Lax`, and sends an updated CSRF token back to the browser.

### B. How Smart Trip Planning Works
1. The traveler selects a destination, start date, end date, travelers, and budget.
2. [`TripService.saveTrip()`](file:///d:/Github/TRIP-PLANNER/src/main/java/com/tripplanner/TripService.java#L158) calculates the exact days: `ChronoUnit.DAYS.between(startDate, endDate) + 1`.
3. **Smart Itinerary**: It reads the destination's top attractions (e.g. Solang Valley, Jogini Falls for Manali) and distributes them across each day at 09:00 AM as starting itinerary suggestions!
4. **Smart Packing Checklist**: It checks the destination season:
   - If **Winter**: auto-adds *"Warm layers"*.
   - If **Monsoon**: auto-adds *"Rain jacket and waterproof bag"*.
   - If **Summer**: auto-adds *"Sunscreen and reusable water bottle"*.
   - Plus standard essentials: Clothes, Documents, Toiletries, Electronics.
5. The traveler can now edit, check off, or add new activities in real-time.

### C. How Package Booking & Simulated Payments Work
1. Traveler browses curated packages (e.g. "Scenic Manali Escape", ₹14,000 / person).
2. Clicking "Book" opens a modal showing package inclusions, hotel, and transport details.
3. The booking submission captures an **immutable JSON snapshot** of the package at that moment. Even if an admin edits the package catalog later, the traveler's booked contract remains preserved!
4. The traveler can click **"Simulate full payment"**:
   - Creates a `RECEIPT` entry in `booking_payments`.
   - The ledger automatically subtracts payments from the booking total.
   - When balance reaches ₹0.00, status becomes `PAID`.
   - If cancelled and money was recorded, it flags `Refund review needed`.

---

## 5. Security Architecture (Viva & Interview Ready)

| Security Feature | How It Is Implemented | Why It Matters |
| :--- | :--- | :--- |
| **Password Hashing** | PBKDF2-HMAC-SHA256 with unique 16-byte salt, 600,000 iterations. | Prevents rainbow-table attacks and makes brute-forcing computationally unfeasible. |
| **SQL Injection Prevention** | 100% of queries use JDBC `PreparedStatement` with `?` parameters. | User input is treated strictly as data, never interpreted as executable SQL commands. |
| **CSRF Protection** | Unique random token required in the `X-CSRF-Token` header for all `POST` requests. | Prevents malicious third-party websites from forging requests on behalf of logged-in users. |
| **XSS Protection** | HTML escaping in `escapeHtml()`, Content-Security-Policy header, and HttpOnly cookies. | Blocks injected JavaScript from stealing credentials or tampering with pages. |
| **Rate Limiting** | `RequestGuard` token bucket filter tracking IP address and request frequency. | Protects authentication endpoints from credential-stuffing bots and server DoS attacks. |
| **Safe Photo Processing** | Server-side BufferedImage resizing, pixel limits (20M max), metadata stripping. | Defeats image decompression bombs (Zip/Image bombs) and protects user privacy. |

---

## 6. Top 10 Viva & Review Questions & Answers

**Q1: What architecture does this application follow?**  
> *Answer*: It follows the standard **MVC (Model-View-Controller)** pattern. Jakarta Servlets (`ApiController`, `PageController`) act as Controllers; JSP and vanilla JavaScript render the Views; and Java service classes (`TripService`, `PackageService`, `Database`) manage the Model and database operations.

**Q2: Why did you build this with pure Servlets instead of Spring Boot?**  
> *Answer*: Using pure Servlets and JDBC provides complete transparency, lightweight memory footprint (runs on small VPS/containers), fast startup (under 2 seconds), and demonstrates foundational Java Enterprise EE / Jakarta EE principles without framework bloat.

**Q3: How do you prevent SQL Injection?**  
> *Answer*: We never concatenate user strings directly into SQL queries. Every database operation uses JDBC `PreparedStatement` where query parameters are bound using safe placeholder indexes (`?`), ensuring parameters cannot modify the query syntax.

**Q4: How are user sessions handled?**  
> *Answer*: Sessions are stored in a database table called `web_sessions` rather than server memory. This ensures sessions survive server restarts and allows seamless horizontal scaling across multiple web instances.

**Q5: How does the application prevent double-booking on network retries?**  
> *Answer*: Every booking request submits an idempotent `request_key` generated on the client. If a network glitch causes the user or browser to submit twice, the database identifies the duplicate key and returns the existing booking rather than creating a duplicate.

**Q6: What happens if two administrators edit a package simultaneously?**  
> *Answer*: We use **Optimistic Concurrency Control**. Each travel package has a `version` number. When updating, if the submitted version does not match the current database version, the update is rejected with an error asking the admin to reload.

**Q7: How is password verification made safe from timing attacks?**  
> *Answer*: We use `MessageDigest.isEqual()`, which compares bytes in constant time. Normal string comparison exits early on the first mismatched character, which allows attackers to measure response times to guess passwords. Constant-time comparison eliminates this vulnerability.

**Q8: What is the purpose of `AppListener.java`?**  
> *Answer*: It implements `ServletContextListener`. It acts as an application lifecycle hook that initializes the HikariCP connection pool and executes schema migrations when Tomcat starts, and cleanly closes all connections when Tomcat stops.

**Q9: How does the single-page application (SPA) router work?**  
> *Answer*: The client-side router in `app.js` listens to the browser `window.onhashchange` event. When the user navigates to `#explore`, `#itinerary`, or `#budget`, the router parses the route, fetches data asynchronously via `fetch('api/...')`, and updates the DOM without reloading the full page.

**Q10: How are database migrations managed?**  
> *Answer*: The `schema_version` table keeps track of executed migration versions. On startup, `Database.initialize()` checks which versions (v1 to v4) are missing and applies only the pending migration scripts sequentially.
