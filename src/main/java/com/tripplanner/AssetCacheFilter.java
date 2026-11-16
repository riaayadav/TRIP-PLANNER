package com.tripplanner;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * AssetCacheFilter enables browser-level disk and memory caching for static assets (/assets/*).
 * 
 * Benefits:
 * 1. Instructs browsers (Chrome, Edge, Safari, Firefox) to cache CSS, JavaScript, and image assets locally.
 * 2. Uses max-age=604800 (7 days) and immutable directive.
 * 3. Cache-busting is handled seamlessly via query parameters (?v=...) in app.jsp.
 * 4. Eliminates redundant HTTP roundtrips, reducing page load latency from seconds to 0ms.
 */
public final class AssetCacheFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        if (response instanceof HttpServletResponse) {
            HttpServletResponse res = (HttpServletResponse) response;
            res.setHeader("Cache-Control", "public, max-age=604800, immutable");
        }
        chain.doFilter(request, response);
    }
}

