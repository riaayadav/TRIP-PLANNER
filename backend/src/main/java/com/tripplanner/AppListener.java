package com.tripplanner;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * AppListener manages the application lifecycle (Startup and Shutdown).
 * 
 * In Java web applications:
 * - When Apache Tomcat starts up, contextInitialized() is triggered.
 *   Here, we initialize the database pool, apply schema migrations, and save
 *   the Database instance into the application-wide ServletContext.
 * - When the web server stops, contextDestroyed() cleanly closes the connection pool.
 */
public final class AppListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        try {
            // 1. Initialize the Database (HikariCP connection pool and schema migrations)
            Database database = new Database();
            database.initialize();

            // 2. Store the database instance in ServletContext so servlets can access it
            event.getServletContext().setAttribute("db", database);
            System.out.println("Trip Planner web application successfully started.");
        } catch (Exception e) {
            throw new IllegalStateException("Trip Planner database startup failed", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        // Cleanly close the database connection pool on server shutdown
        Object dbAttribute = event.getServletContext().getAttribute("db");
        if (dbAttribute instanceof Database) {
            Database database = (Database) dbAttribute;
            database.close();
            System.out.println("Trip Planner database pool cleanly closed.");
        }
    }
}
