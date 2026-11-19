package com.tripplanner;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * PageController serves the frontend Single-Page Application (SPA).
 * 
 * In the Java MVC pattern:
 * - This acts as the View Controller.
 * - When users visit "/", it sets standard HTTP security headers and forwards
 *   the request to the private "/WEB-INF/app.jsp" template.
 * - Placing app.jsp inside /WEB-INF ensures visitors cannot bypass this controller.
 */
public final class PageController extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {

        // 1. Prevent browsers from caching sensitive user pages
        response.setHeader("Cache-Control", "no-store");

        // 2. Prevent MIME-type sniffing attacks
        response.setHeader("X-Content-Type-Options", "nosniff");

        // 3. Only send referrer information for same-origin requests
        response.setHeader("Referrer-Policy", "same-origin");

        // 4. Content Security Policy (CSP): Restrict script and asset sources to self
        response.setHeader(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' blob: data:; style-src 'self'; script-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'"
        );

        // 5. Forward the request to the main JSP view file
        request.getRequestDispatcher("/WEB-INF/app.jsp").forward(request, response);
    }
}
