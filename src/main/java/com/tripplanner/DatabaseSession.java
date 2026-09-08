package com.tripplanner;

import static com.tripplanner.Database.*;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Shared sessions survive container replacement. Only token digests are stored. */
final class DatabaseSession {

    private static final long IDLE_MILLIS = 30 * 60 * 1000L;
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

    static DatabaseSession load(
        Connection connection,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws SQLException {
        long now = System.currentTimeMillis();
        String token = null;
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies())
            if (COOKIE.equals(cookie.getName())) token = cookie.getValue();
        if (ThreadLocalRandom.current().nextInt(100) == 0) execute(
            connection,
            "DELETE FROM web_sessions WHERE expires_at<? LIMIT 500",
            now
        );
        if (token != null && token.matches("[A-Za-z0-9_-]{43}")) {
            String key = digest(token);
            List<Map<String, Object>> rows = query(
                connection,
                "SELECT uid,auth_version,csrf FROM web_sessions WHERE session_key=? AND expires_at>?",
                key,
                now
            );
            if (
                !rows.isEmpty() &&
                execute(
                    connection,
                    "UPDATE web_sessions SET expires_at=? WHERE session_key=? AND expires_at>?",
                    now + IDLE_MILLIS,
                    key,
                    now
                ) == 1
            ) return new DatabaseSession(connection, request, response, key, rows.get(0));
        }
        String fresh = Security.token(),
            key = digest(fresh),
            csrf = Security.token();
        execute(
            connection,
            "INSERT INTO web_sessions(session_key,csrf,expires_at) VALUES (?,?,?)",
            key,
            csrf,
            now + IDLE_MILLIS
        );
        DatabaseSession session = new DatabaseSession(
            connection,
            request,
            response,
            key,
            Map.of("csrf", csrf)
        );
        session.cookie(fresh, false);
        return session;
    }

    Object getAttribute(String name) {
        return values.get(name.equals("version") ? "auth_version" : name);
    }

    void authenticate(long user, int version) throws SQLException {
        String fresh = Security.token(),
            newKey = digest(fresh),
            csrf = Security.token();
        long now = System.currentTimeMillis();
        // One atomic update rotates the ID and authentication state together.
        if (
            execute(
                connection,
                "UPDATE web_sessions SET session_key=?,uid=?,auth_version=?,csrf=?,expires_at=? WHERE session_key=? AND expires_at>?",
                newKey,
                user,
                version,
                csrf,
                now + IDLE_MILLIS,
                key,
                now
            ) != 1
        ) throw new IllegalArgumentException("Your session changed. Refresh and sign in again.");
        key = newKey;
        values = Map.of("uid", user, "auth_version", version, "csrf", csrf);
        cookie(fresh, false);
    }

    void invalidate() throws SQLException {
        execute(connection, "DELETE FROM web_sessions WHERE session_key=?", key);
        values = Map.of();
        cookie("", true);
    }

    private void cookie(String token, boolean expired) {
        Cookie cookie = new Cookie(COOKIE, token);
        cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure() || Boolean.parseBoolean(env("APP_HTTPS", "false")));
        cookie.setAttribute("SameSite", "Lax");
        if (expired) cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    static String digest(String token) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    token.getBytes(StandardCharsets.US_ASCII)
                )
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
