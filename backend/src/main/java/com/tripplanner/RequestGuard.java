package com.tripplanner;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RequestGuard is a Servlet Filter that protects the application against abuse.
 * 
 * How it works:
 * 1. Implements a Token Bucket / Sliding Window rate limiter per client IP address.
 * 2. Distinguishes three types of traffic:
 *    - Authentication (/login, /register): Strictest limit (50 requests per 15 min) to stop brute-forcing.
 *    - Write requests (POST): Moderate limit (120 requests per minute) to stop spam submissions.
 *    - Read requests (GET): High throughput (300 requests per minute) for smooth browsing.
 * 3. When limits are exceeded, responds with HTTP 429 (Too Many Requests).
 */
public final class RequestGuard implements Filter {

    /**
     * Helper data holder for tracking the request count and start timestamp for each client.
     */
    private static class Bucket {
        private final long started;
        private final int requests;

        public Bucket(long started, int requests) {
            this.started = started;
            this.requests = requests;
        }

        public long getStarted() {
            return started;
        }

        public int getRequests() {
            return requests;
        }
    }

    // Thread-safe map storing rate-limit buckets keyed by "IP:actionType"
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String path = req.getRequestURI();
        boolean isAuth = path.endsWith("/login") || path.endsWith("/register");
        boolean isWrite = "POST".equalsIgnoreCase(req.getMethod());

        // Step 1: Define time window in milliseconds
        // Auth requests use a 15-minute window; normal requests use a 1-minute window
        long window = isAuth ? 900000L : 60000L;

        // Step 2: Define maximum allowed requests within the time window
        int maxRequests;
        if (isAuth) {
            maxRequests = 50;  // 50 attempts per 15 minutes for login/register
        } else if (isWrite) {
            maxRequests = 120; // 120 writes per minute
        } else {
            maxRequests = 300; // 300 reads per minute
        }

        // Step 3: Determine action type category for the key
        String actionType = isAuth ? "auth" : (isWrite ? "write" : "read");
        String key = req.getRemoteAddr() + ":" + actionType;
        long now = System.currentTimeMillis();

        // Step 4: Periodic memory cleanup - remove expired buckets if map gets large
        if (buckets.size() > 10000) {
            Iterator<Map.Entry<String, Bucket>> iterator = buckets.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Bucket> entry = iterator.next();
                if (now - entry.getValue().getStarted() > 900000L) {
                    iterator.remove();
                }
            }
        }

        // Circuit breaker: protect server memory if under heavy DDoS attack
        if (buckets.size() > 20000 && !buckets.containsKey(key)) {
            reject(res);
            return;
        }

        // Step 5: Check and update the request count for this client key
        Bucket current;
        synchronized (buckets) {
            Bucket existing = buckets.get(key);
            if (existing == null || (now - existing.getStarted()) > window) {
                // New window started
                current = new Bucket(now, 1);
            } else {
                // Increment counter within current window
                current = new Bucket(existing.getStarted(), existing.getRequests() + 1);
            }
            buckets.put(key, current);
        }

        // Step 6: If limit exceeded, return HTTP 429
        if (current.getRequests() > maxRequests) {
            reject(res);
            return;
        }

        // Step 7: Request is within limits, pass it down the filter chain
        chain.doFilter(request, response);
    }

    /**
     * Sends an HTTP 429 "Too Many Requests" JSON response.
     */
    private void reject(HttpServletResponse res) throws IOException {
        res.setStatus(429);
        res.setContentType("application/json");
        res.setHeader("Retry-After", "60");
        res.getWriter().write("{\"error\":\"Too many requests. Please wait and try again.\"}");
    }
}
