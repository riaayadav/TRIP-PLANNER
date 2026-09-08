package com.tripplanner;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded per-instance abuse protection; deploy edge limits for multi-instance systems. */
public final class RequestGuard implements Filter {

    private record Bucket(long started, int requests) {}

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();
        boolean auth = path.endsWith("/login") || path.endsWith("/register");
        boolean write = req.getMethod().equals("POST");
        long window = auth ? 900000 : 60000;
        int max = auth ? 50 : write ? 120 : 300;
        String key = req.getRemoteAddr() + ":" + (auth ? "auth" : write ? "write" : "read");
        long now = System.currentTimeMillis();
        if (buckets.size() > 10000) buckets
            .entrySet()
            .removeIf(e -> now - e.getValue().started() > 900000);
        if (buckets.size() > 20000 && !buckets.containsKey(key)) {
            reject(res);
            return;
        }
        Bucket current = buckets.compute(key, (k, b) ->
            b == null || now - b.started() > window
                ? new Bucket(now, 1)
                : new Bucket(b.started(), b.requests() + 1)
        );
        if (current.requests() > max) {
            reject(res);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse res) throws IOException {
        res.setStatus(429);
        res.setContentType("application/json");
        res.setHeader("Retry-After", "60");
        res.getWriter().write("{\"error\":\"Too many requests. Please wait and try again.\"}");
    }
}
