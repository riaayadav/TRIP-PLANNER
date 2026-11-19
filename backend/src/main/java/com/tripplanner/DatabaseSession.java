package com.tripplanner;

import static com.tripplanner.Database.*;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DatabaseSession provides database-backed user session management.
 * 
 * Why Database-Backed Sessions?
 * - Standard in-memory sessions are lost when a web server restarts.
 * - Storing sessions in the database (the `web_sessions` table) allows session persistence
 *   across restarts and enables multi-server horizontal scaling.
 * 
 * Security Features:
 * 1. Token Hashing: The database stores only the SHA-256 digest of the cookie token.
 *    Even if the database is leaked, an attacker cannot forge session cookies.
 * 2. Session Rotation: On login, a new session key and new CSRF token are generated
 *    to prevent Session Fixation attacks.
 * 3. Cookie Flags: HttpOnly (blocks XSS access), SameSite=Lax (blocks CSRF), and Secure.
 * 4. Automatic Expiry: Sessions automatically expire after 30 minutes of inactivity.
 */
final class DatabaseSession {

    // Session idle timeout: 30 minutes in milliseconds
    private static final long IDLE_MILLIS = 30 * 60 * 1000L;
    private static final String COOKIE_NAME = "ROAMSESSION";

    private final Connection connection;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private String key;
    private Map<String, Object> values;

    private DatabaseSession(
        Connection connection,
        HttpServletRequest request,
        HttpServletResponse response,
        String key,
        Map<String, Object> values
    ) {
        this.connection = connection;
        this.request = request;
        this.response = response;
        this.key = key;
        this.values = values;
    }

    /**
     * Loads an existing active session from the HTTP cookie, or creates a new one.
     */
    static DatabaseSession load(
        Connection connection,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws SQLException {
        long now = System.currentTimeMillis();
        String token = null;

        // Step 1: Read the ROAMSESSION cookie from request headers
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (int i = 0; i < cookies.length; i++) {
                Cookie cookie = cookies[i];
                if (COOKIE_NAME.equals(cookie.getName())) {
                    token = cookie.getValue();
                    break;
                }
            }
        }

        // Step 2: Occasional lazy cleanup (1% of requests) of expired sessions
        if (ThreadLocalRandom.current().nextInt(100) == 0) {
            execute(
                connection,
                "DELETE FROM web_sessions WHERE expires_at<? LIMIT 500",
                now
            );
        }

        // Step 3: If cookie exists and has valid format, look it up in the database
        if (token != null && token.matches("[A-Za-z0-9_-]{43}")) {
            String sessionKey = digest(token);
            List<Map<String, Object>> rows = query(
                connection,
                "SELECT uid,auth_version,csrf FROM web_sessions WHERE session_key=? AND expires_at>?",
                sessionKey,
                now
            );

            // If session found, extend its expiration time by another 30 minutes
            if (!rows.isEmpty()) {
                int updated = execute(
                    connection,
                    "UPDATE web_sessions SET expires_at=? WHERE session_key=? AND expires_at>?",
                    now + IDLE_MILLIS,
                    sessionKey,
                    now
                );
                if (updated == 1) {
                    return new DatabaseSession(connection, request, response, sessionKey, rows.get(0));
                }
            }
        }

        // Step 4: No valid session found - create a brand new guest session
        String freshToken = Security.token();
        String sessionKey = digest(freshToken);
        String csrfToken = Security.token();

        execute(
            connection,
            "INSERT INTO web_sessions(session_key,csrf,expires_at) VALUES (?,?,?)",
            sessionKey,
            csrfToken,
            now + IDLE_MILLIS
        );

        Map<String, Object> sessionValues = new HashMap<>();
        sessionValues.put("csrf", csrfToken);

        DatabaseSession session = new DatabaseSession(
            connection,
            request,
            response,
            sessionKey,
            sessionValues
        );
        session.cookie(freshToken, false);
        return session;
    }

    /**
     * Retrieves a stored session attribute (e.g., 'uid', 'csrf', 'auth_version').
     */
    Object getAttribute(String name) {
        String lookupKey = name;
        if ("version".equals(name)) {
            lookupKey = "auth_version";
        }
        return values.get(lookupKey);
    }

    /**
     * Authenticates the user and rotates both the session token and CSRF token.
     * Rotating session tokens prevents Session Fixation vulnerabilities.
     */
    void authenticate(long userId, int version) throws SQLException {
        String freshToken = Security.token();
        String newKey = digest(freshToken);
        String csrfToken = Security.token();
        long now = System.currentTimeMillis();

        int updated = execute(
            connection,
            "UPDATE web_sessions SET session_key=?,uid=?,auth_version=?,csrf=?,expires_at=? WHERE session_key=? AND expires_at>?",
            newKey,
            userId,
            version,
            csrfToken,
            now + IDLE_MILLIS,
            key,
            now
        );

        if (updated != 1) {
            throw new IllegalArgumentException("Your session changed. Refresh and sign in again.");
        }

        this.key = newKey;
        Map<String, Object> newValues = new HashMap<>();
        newValues.put("uid", userId);
        newValues.put("auth_version", version);
        newValues.put("csrf", csrfToken);
        this.values = newValues;

        // Set the new rotated cookie on the response
        cookie(freshToken, false);
    }

    /**
     * Logs out the user by deleting the session row from the database and clearing the cookie.
     */
    void invalidate() throws SQLException {
        execute(connection, "DELETE FROM web_sessions WHERE session_key=?", key);
        this.values = new HashMap<>();
        cookie("", true);
    }

    /**
     * Attaches the session cookie to the HTTP response with secure attributes.
     */
    private void cookie(String token, boolean expired) {
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty()) {
            cookie.setPath("/");
        } else {
            cookie.setPath(contextPath);
        }
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure() || Boolean.parseBoolean(env("APP_HTTPS", "false")));
        cookie.setAttribute("SameSite", "Lax");
        if (expired) {
            cookie.setMaxAge(0); // Immediately expires cookie on client
        }
        response.addCookie(cookie);
    }

    /**
     * Computes the SHA-256 hexadecimal digest of a token string.
     */
    static String digest(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(token.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < hashBytes.length; i++) {
                String hex = Integer.toHexString(0xff & hashBytes[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate SHA-256 digest", e);
        }
    }
}
