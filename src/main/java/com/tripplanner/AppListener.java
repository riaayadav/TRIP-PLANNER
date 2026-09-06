package com.tripplanner;
import jakarta.servlet.*;
public final class AppListener implements ServletContextListener {
    public void contextInitialized(ServletContextEvent event){try{Database db=new Database();db.initialize();event.getServletContext().setAttribute("db",db);}catch(Exception e){throw new IllegalStateException("Trip Planner database startup failed",e);}}
    public void contextDestroyed(ServletContextEvent event){Object db=event.getServletContext().getAttribute("db");if(db instanceof Database d)d.close();}
}
