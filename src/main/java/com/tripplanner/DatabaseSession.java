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

// DatabaseSession manages user sessions stored in the MySQL/H2 database
final class DatabaseSession {

    private static final long IDLE_MILLIS = 30 * 60 * 1000L; // 30 minutes
    private static final String COOKIE = "ROAMSESSION";
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

    // Loads an existing session from cookie or creates a new one
    static DatabaseSession load(
        Connection connection,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws SQLException {
        long now = System.currentTimeMillis();
        String token = null;

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (int i = 0; i < cookies.length; i++) {
                Cookie cookie = cookies[i];
                if (COOKIE.equals(cookie.getName())) {
                    token = cookie.getValue();
                    break;
                }
            }
        }

        // Random cleanup of expired sessions
        if (ThreadLocalRandom.current().nextInt(100) == 0) {
            execute(
                connection,
                "DELETE FROM web_sessions WHERE expires_at<? LIMIT 500",
                now
            );
        }

        // Validate token format and check if session exists in DB
        if (token != null && token.matches("[A-Za-z0-9_-]{43}")) {
            String sessionKey = digest(token);
            List<Map<String, Object>> rows = query(
                connection,
                "SELECT uid,auth_version,csrf FROM web_sessions WHERE session_key=? AND expires_at>?",
                sessionKey,
                now
            );

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

        // Create a new session if not found or expired
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

    // Retrieves a session attribute by name
    Object getAttribute(String name) {
        String lookupKey = name;
        if ("version".equals(name)) {
            lookupKey = "auth_version";
        }
        return values.get(lookupKey);
    }

    // Authenticates user and rotates session token
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

        key = newKey;
        Map<String, Object> newValues = new HashMap<>();
        newValues.put("uid", userId);
        newValues.put("auth_version", version);
        newValues.put("csrf", csrfToken);
        values = newValues;

        cookie(freshToken, false);
    }

    // Invalidates and removes current session
    void invalidate() throws SQLException {
        execute(connection, "DELETE FROM web_sessions WHERE session_key=?", key);
        values = new HashMap<>();
        cookie("", true);
    }

    // Sets session cookie on response
    private void cookie(String token, boolean expired) {
        Cookie cookie = new Cookie(COOKIE, token);
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
            cookie.setMaxAge(0);
        }
        response.addCookie(cookie);
    }

    // Generates SHA-256 hex digest for token
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
            throw new IllegalStateException(e);
        }
    }
}
