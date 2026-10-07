package com.tripplanner;

import jakarta.servlet.*;

// AppListener initializes the database when the web application starts and closes it on shutdown
public final class AppListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        try {
            Database db = new Database();
            db.initialize();
            event.getServletContext().setAttribute("db", db);
        } catch (Exception e) {
            throw new IllegalStateException("Trip Planner database startup failed", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        Object db = event.getServletContext().getAttribute("db");
        if (db instanceof Database) {
            Database database = (Database) db;
            database.close();
        }
    }
}
