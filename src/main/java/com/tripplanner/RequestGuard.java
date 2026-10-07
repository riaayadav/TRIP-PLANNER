package com.tripplanner;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// RequestGuard filters incoming requests to prevent rate-limit abuse
public final class RequestGuard implements Filter {

    // Simple helper class to store request count and timestamp
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

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String path = req.getRequestURI();
        boolean isAuth = path.endsWith("/login") || path.endsWith("/register");
        boolean isWrite = req.getMethod().equals("POST");

        long window;
        if (isAuth) {
            window = 900000; // 15 minutes
        } else {
            window = 60000;  // 1 minute
        }

        int maxRequests;
        if (isAuth) {
            maxRequests = 50;
        } else if (isWrite) {
            maxRequests = 120;
        } else {
            maxRequests = 300;
        }

        String actionType = "read";
        if (isAuth) {
            actionType = "auth";
        } else if (isWrite) {
            actionType = "write";
        }

        String key = req.getRemoteAddr() + ":" + actionType;
        long now = System.currentTimeMillis();

        // Clean old entries if map gets too large
        if (buckets.size() > 10000) {
            Iterator<Map.Entry<String, Bucket>> iterator = buckets.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Bucket> entry = iterator.next();
                if (now - entry.getValue().getStarted() > 900000) {
                    iterator.remove();
                }
            }
        }

        if (buckets.size() > 20000 && !buckets.containsKey(key)) {
            reject(res);
            return;
        }

        Bucket current;
        synchronized (buckets) {
            Bucket existing = buckets.get(key);
            if (existing == null || (now - existing.getStarted()) > window) {
                current = new Bucket(now, 1);
            } else {
                current = new Bucket(existing.getStarted(), existing.getRequests() + 1);
            }
            buckets.put(key, current);
        }

        if (current.getRequests() > maxRequests) {
            reject(res);
            return;
        }

        chain.doFilter(request, response);
    }

    // Sends HTTP 429 Too Many Requests response
    private void reject(HttpServletResponse res) throws IOException {
        res.setStatus(429);
        res.setContentType("application/json");
        res.setHeader("Retry-After", "60");
        res.getWriter().write("{\"error\":\"Too many requests. Please wait and try again.\"}");
    }
}
