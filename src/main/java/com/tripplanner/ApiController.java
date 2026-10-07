package com.tripplanner;

import static com.tripplanner.Database.*;
import static com.tripplanner.TripService.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

// ApiController is the main servlet that handles all REST API requests from the frontend
public final class ApiController extends HttpServlet {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DUMMY_PASSWORD_HASH = Security.hash("dummy-password-for-timing-only");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    // Helper to get the Database singleton from ServletContext
    private Database getDatabase() {
        return (Database) getServletContext().getAttribute("db");
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
        request.setCharacterEncoding("UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");

        String method = request.getMethod();
        String path = "";
        if (request.getPathInfo() != null) {
            path = request.getPathInfo();
        }

        // Only GET and POST methods are permitted
        if (!"GET".equals(method) && !"POST".equals(method)) {
            error(response, 405, "Method not allowed.");
            return;
        }

        try (Connection connection = getDatabase().connection()) {
            // Health check endpoint
            if (path.equals("/health") && method.equals("GET")) {
                query(connection, "SELECT 1");
                Map<String, Object> healthMap = new HashMap<>();
                healthMap.put("status", "ok");
                write(response, healthMap);
                return;
            }

            DatabaseSession session = DatabaseSession.load(connection, request, response);
            Map<String, Object> user = null;

            // Check if user is logged in
            Object sessionUid = session.getAttribute("uid");
            if (sessionUid instanceof Number) {
                long loggedInUserId = ((Number) sessionUid).longValue();
                List<Map<String, Object>> userRows = query(
                    connection,
                    "SELECT id,name,email,role,auth_version FROM users WHERE id=? AND active=TRUE",
                    loggedInUserId
                );
                if (!userRows.isEmpty() && Objects.equals(userRows.get(0).get("auth_version"), session.getAttribute("version"))) {
                    user = userRows.get(0);
                } else {
                    session.invalidate();
                    error(response, 401, "Please sign in again.");
                    return;
                }
            }

            // Public image endpoint for package photos
            if (path.equals("/package-photo") && method.equals("GET")) {
                long photoPackageId = parseId(request.getParameter("id"));
                List<Map<String, Object>> rows = query(
                    connection,
                    "SELECT photo FROM package_photos WHERE package_id=?",
                    photoPackageId
                );
                if (rows.isEmpty() || rows.get(0).get("photo") == null) {
                    throw new Missing();
                }
                Object rawPhoto = rows.get(0).get("photo");
                byte[] photoBytes;
                if (rawPhoto instanceof Blob) {
                    Blob blob = (Blob) rawPhoto;
                    photoBytes = blob.getBytes(1, (int) blob.length());
                } else {
                    photoBytes = (byte[]) rawPhoto;
                }
                response.setCharacterEncoding(null);
                response.setContentType("image/jpeg");
                response.setHeader("Cache-Control", "public, max-age=86400");
                response.setContentLength(photoBytes.length);
                response.getOutputStream().write(photoBytes);
                return;
            }

            // Session info endpoint
            if (path.equals("/session") && method.equals("GET")) {
                Map<String, Object> sessionInfo = new LinkedHashMap<>();
                sessionInfo.put("csrf", session.getAttribute("csrf"));
                sessionInfo.put("user", user);
                sessionInfo.put("settings", settings(connection));
                write(response, sessionInfo);
                return;
            }

            Map<String, Object> body = new LinkedHashMap<>();

            // Parse and validate POST requests
            if (method.equals("POST")) {
                Object sessionCsrf = session.getAttribute("csrf");
                String headerCsrf = request.getHeader("X-CSRF-Token");
                if (!Objects.equals(sessionCsrf, headerCsrf)) {
                    error(response, 403, "Your session changed. Refresh and try again.");
                    return;
                }

                String contentType = request.getContentType();
                if (contentType == null || !contentType.startsWith("application/json")) {
                    error(response, 415, "JSON content is required.");
                    return;
                }

                byte[] requestBytes = request.getInputStream().readNBytes(4400001);
                if (requestBytes.length > 4400000) {
                    error(response, 413, "Upload is too large.");
                    return;
                }

                try {
                    body = JSON.readValue(requestBytes, MAP_TYPE);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid JSON request.");
                }

                if (body == null) {
                    throw new IllegalArgumentException("A JSON object is required.");
                }

                if (path.equals("/login") || path.equals("/register")) {
                    authenticate(connection, session, response, body, path);
                    return;
                }

                if (path.equals("/logout")) {
                    session.invalidate();
                    Map<String, Object> okResponse = new HashMap<>();
                    okResponse.put("ok", true);
                    write(response, okResponse);
                    return;
                }
            }

            // All subsequent endpoints require authentication
            if (user == null) {
                error(response, 401, "Please sign in to continue.");
                return;
            }

            long currentUserId = ((Number) user.get("id")).longValue();

            // Admin authorization check
            if (path.startsWith("/admin") && !"ADMIN".equals(user.get("role"))) {
                error(response, 403, "Administrator access is required.");
                return;
            }

            // Handle authenticated GET requests
            if (method.equals("GET")) {
                if (path.equals("/packages")) {
                    write(response, PackageService.overview(connection, currentUserId, false));
                    return;
                }

                if (path.equals("/admin/packages")) {
                    write(response, PackageService.overview(connection, currentUserId, true));
                    return;
                }

                if (path.equals("/destination-photo")) {
                    long destinationPhotoId = parseId(request.getParameter("id"));
                    boolean isAdmin = "ADMIN".equals(user.get("role"));
                    List<Map<String, Object>> rows = query(
                        connection,
                        "SELECT p.photo FROM destination_photos p JOIN destinations d ON d.id=p.destination_id WHERE p.destination_id=? AND (d.active=TRUE OR ?=TRUE)",
                        destinationPhotoId,
                        isAdmin
                    );
                    if (rows.isEmpty()) {
                        throw new Missing();
                    }
                    Object rawPhoto = rows.get(0).get("photo");
                    byte[] photoBytes;
                    if (rawPhoto instanceof Blob) {
                        Blob blob = (Blob) rawPhoto;
                        photoBytes = blob.getBytes(1, (int) blob.length());
                    } else {
                        photoBytes = (byte[]) rawPhoto;
                    }
                    response.setCharacterEncoding(null);
                    response.setContentType("image/jpeg");
                    response.setContentLength(photoBytes.length);
                    response.getOutputStream().write(photoBytes);
                    return;
                }

                if (path.equals("/data")) {
                    write(response, data(connection, currentUserId));
                    return;
                }

                if (path.equals("/trip")) {
                    long tripDetailId = parseId(request.getParameter("id"));
                    Map<String, Object> tripData = owned(connection, currentUserId, tripDetailId);
                    tripData.put(
                        "itinerary",
                        query(
                            connection,
                            "SELECT * FROM itinerary WHERE trip_id=? ORDER BY day_number,time_slot,id",
                            tripDetailId
                        )
                    );
                    tripData.put(
                        "expenses",
                        query(connection, "SELECT * FROM expenses WHERE trip_id=? ORDER BY id", tripDetailId)
                    );
                    tripData.put(
                        "packing",
                        query(connection, "SELECT * FROM packing WHERE trip_id=? ORDER BY id", tripDetailId)
                    );
                    write(response, tripData);
                    return;
                }

                if (path.equals("/photo")) {
                    long photoMemoryId = parseId(request.getParameter("id"));
                    List<Map<String, Object>> rows = query(
                        connection,
                        "SELECT photo FROM memories WHERE id=? AND user_id=?",
                        photoMemoryId,
                        currentUserId
                    );
                    if (rows.isEmpty() || rows.get(0).get("photo") == null) {
                        throw new Missing();
                    }
                    Object rawPhoto = rows.get(0).get("photo");
                    byte[] photoBytes;
                    if (rawPhoto instanceof Blob) {
                        Blob blob = (Blob) rawPhoto;
                        photoBytes = blob.getBytes(1, (int) blob.length());
                    } else {
                        photoBytes = (byte[]) rawPhoto;
                    }
                    response.setCharacterEncoding(null);
                    response.setContentType("image/jpeg");
                    response.setContentLength(photoBytes.length);
                    response.getOutputStream().write(photoBytes);
                    return;
                }

                if (path.equals("/admin")) {
                    write(response, adminData(connection, request));
                    return;
                }

                throw new Missing();
            }

            // Handle authenticated POST requests inside a transaction
            connection.setAutoCommit(false);
            try {
                // Lock the user's row to prevent concurrent race conditions
                query(connection, "SELECT id FROM users WHERE id=? FOR UPDATE", currentUserId);

                Map<String, Object> output = new LinkedHashMap<>();
                output.put("ok", true);

                if (path.equals("/admin/package")) {
                    output.put("id", PackageService.save(connection, currentUserId, body));
                } else if (path.equals("/admin/hotel")) {
                    output.put("id", PackageService.supplier(connection, currentUserId, body, true));
                } else if (path.equals("/admin/transport")) {
                    output.put("id", PackageService.supplier(connection, currentUserId, body, false));
                } else if (path.equals("/admin/payment")) {
                    output.put("id", PackageService.payment(connection, currentUserId, body));
                } else if (path.equals("/admin/booking-status")) {
                    PackageService.status(connection, currentUserId, body, true);
                } else if (path.equals("/booking")) {
                    if (!"CLIENT".equals(user.get("role"))) {
                        throw new IllegalArgumentException("Use a traveler account to request a package.");
                    }
                    output.put("id", PackageService.book(connection, currentUserId, body));
                } else if (path.equals("/booking-status")) {
                    PackageService.status(connection, currentUserId, body, false);
                } else if (path.equals("/booking-demo-payment")) {
                    output.put("id", PackageService.demoPayment(connection, currentUserId, body));
                } else if (path.equals("/trip")) {
                    output.put("id", saveTrip(connection, currentUserId, body));
                } else if (path.equals("/trip-status")) {
                    long tripId = number(body, "id", 1, Long.MAX_VALUE);
                    Map<String, Object> trip = owned(connection, currentUserId, tripId);
                    editable(trip);
                    String status = choice(body, "status", "CANCELLED", "COMPLETED");
                    if ("COMPLETED".equals(status)) {
                        LocalDate tripEnd = LocalDate.parse(trip.get("end_date").toString());
                        if (tripEnd.isAfter(LocalDate.now())) {
                            throw new IllegalArgumentException("A trip can be completed on or after its end date.");
                        }
                    }
                    execute(connection, "UPDATE trips SET status=? WHERE id=?", status, tripId);
                } else if (path.equals("/expenses") || path.equals("/itinerary") || path.equals("/packing")) {
                    String entityName = path.substring(1);
                    child(connection, currentUserId, entityName, body);
                } else if (path.equals("/wishlist")) {
                    long wishlistDestId = number(body, "destination_id", 1, Long.MAX_VALUE);
                    if (Boolean.TRUE.equals(body.get("saved"))) {
                        List<Map<String, Object>> destRows = query(
                            connection,
                            "SELECT id FROM destinations WHERE id=? AND active=TRUE",
                            wishlistDestId
                        );
                        if (destRows.isEmpty()) {
                            throw new Missing();
                        }
                        List<Map<String, Object>> existingWishlist = query(
                            connection,
                            "SELECT user_id FROM wishlist WHERE user_id=? AND destination_id=?",
                            currentUserId,
                            wishlistDestId
                        );
                        if (existingWishlist.isEmpty()) {
                            execute(connection, "INSERT INTO wishlist VALUES (?,?)", currentUserId, wishlistDestId);
                        }
                    } else {
                        execute(
                            connection,
                            "DELETE FROM wishlist WHERE user_id=? AND destination_id=?",
                            currentUserId,
                            wishlistDestId
                        );
                    }
                } else if (path.equals("/memory")) {
                    if ("delete".equals(body.get("action"))) {
                        long memoryId = number(body, "id", 1, Long.MAX_VALUE);
                        int deletedCount = execute(
                            connection,
                            "DELETE FROM memories WHERE id=? AND user_id=?",
                            memoryId,
                            currentUserId
                        );
                        if (deletedCount == 0) {
                            throw new Missing();
                        }
                    } else {
                        long tripId = number(body, "trip_id", 1, Long.MAX_VALUE);
                        Map<String, Object> trip = owned(connection, currentUserId, tripId);
                        completed(trip);
                        LocalDate memoryDate = date(body, "memory_date");
                        LocalDate tripStart = LocalDate.parse(trip.get("start_date").toString());
                        LocalDate tripEnd = LocalDate.parse(trip.get("end_date").toString());
                        if (memoryDate.isBefore(tripStart) || memoryDate.isAfter(tripEnd)) {
                            throw new IllegalArgumentException("Memory date must be within the trip dates.");
                        }

                        List<Map<String, Object>> countRows = query(
                            connection,
                            "SELECT COUNT(*) AS n FROM memories WHERE user_id=?",
                            currentUserId
                        );
                        long currentMemoryCount = ((Number) countRows.get(0).get("n")).longValue();
                        if (currentMemoryCount >= 200) {
                            throw new IllegalArgumentException("Your gallery has reached its 200-memory limit.");
                        }

                        execute(
                            connection,
                            "INSERT INTO memories(user_id,trip_id,title,memory_date,note,photo) VALUES (?,?,?,?,?,?)",
                            currentUserId,
                            tripId,
                            text(body, "title", 150),
                            memoryDate,
                            optional(body, "note", 2000),
                            photo(body)
                        );
                    }
                } else if (path.equals("/feedback")) {
                    long feedbackTripId = number(body, "trip_id", 1, Long.MAX_VALUE);
                    Map<String, Object> trip = owned(connection, currentUserId, feedbackTripId);
                    completed(trip);
                    execute(
                        connection,
                        "INSERT INTO feedback(user_id,trip_id,rating,message) VALUES (?,?,?,?)",
                        currentUserId,
                        feedbackTripId,
                        number(body, "rating", 1, 5),
                        text(body, "message", 2000)
                    );
                } else if (path.equals("/profile")) {
                    String updatedName = text(body, "name", 100);
                    String updatedEmail = email(body);
                    execute(
                        connection,
                        "UPDATE users SET name=?,email=? WHERE id=?",
                        updatedName,
                        updatedEmail,
                        currentUserId
                    );
                } else if (path.equals("/password")) {
                    String oldPassword = password(body, "current_password");
                    String newPassword = password(body, "password");
                    Security.validatePassword(newPassword);

                    List<Map<String, Object>> userRows = query(
                        connection,
                        "SELECT password FROM users WHERE id=?",
                        currentUserId
                    );
                    String storedHash = userRows.get(0).get("password").toString();
                    if (!Security.verify(oldPassword, storedHash)) {
                        throw new IllegalArgumentException("Current password is incorrect.");
                    }

                    execute(
                        connection,
                        "UPDATE users SET password=?,auth_version=auth_version+1 WHERE id=?",
                        Security.hash(newPassword),
                        currentUserId
                    );
                    output.put("sign_in_again", true);
                } else if (path.equals("/admin/destination")) {
                    long destId = 0;
                    if (body.containsKey("id")) {
                        destId = number(body, "id", 1, Long.MAX_VALUE);
                    }

                    String name = text(body, "name", 120);
                    String region = text(body, "region", 120);
                    String description = text(body, "description", 4000);
                    String season = choice(body, "season", "Summer", "Monsoon", "Autumn", "Winter");
                    String category = text(body, "category", 40);
                    String activities = listText(body, "activities", 1000);
                    String attractions = listText(body, "attractions", 1000);

                    String img = "mountains.jpg";
                    if (destId > 0) {
                        List<Map<String, Object>> imgRows = query(
                            connection,
                            "SELECT image FROM destinations WHERE id=? FOR UPDATE",
                            destId
                        );
                        if (imgRows.isEmpty()) {
                            throw new Missing();
                        }
                        img = imgRows.get(0).get("image").toString();
                    }

                    byte[] uploadedPhoto = null;
                    if (body.containsKey("photo")) {
                        uploadedPhoto = photo(body);
                    }

                    if (body.containsKey("image") && uploadedPhoto == null && !img.startsWith("api/")) {
                        img = choice(body, "image", "mountains.jpg", "coast.jpg", "city.jpg");
                    }

                    boolean activeStatus = Boolean.TRUE.equals(body.get("active"));
                    BigDecimal dailyCost = money(body, "daily_cost");
                    long duration = number(body, "duration", 1, 60);

                    if (destId == 0) {
                        destId = insert(
                            connection,
                            "INSERT INTO destinations(name,region,description,season,category,daily_cost,duration,activities,attractions,image,active) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                            name,
                            region,
                            description,
                            season,
                            category,
                            dailyCost,
                            duration,
                            activities,
                            attractions,
                            img,
                            activeStatus
                        );
                    } else {
                        int updated = execute(
                            connection,
                            "UPDATE destinations SET name=?,region=?,description=?,season=?,category=?,daily_cost=?,duration=?,activities=?,attractions=?,image=?,active=? WHERE id=?",
                            name,
                            region,
                            description,
                            season,
                            category,
                            dailyCost,
                            duration,
                            activities,
                            attractions,
                            img,
                            activeStatus,
                            destId
                        );
                        if (updated == 0) {
                            throw new Missing();
                        }
                    }

                    if (uploadedPhoto != null) {
                        execute(connection, "DELETE FROM destination_photos WHERE destination_id=?", destId);
                        execute(
                            connection,
                            "INSERT INTO destination_photos(destination_id,photo) VALUES (?,?)",
                            destId,
                            uploadedPhoto
                        );
                        execute(
                            connection,
                            "UPDATE destinations SET image=? WHERE id=?",
                            "api/destination-photo?id=" + destId,
                            destId
                        );
                    }

                    output.put("id", destId);
                    audit(connection, currentUserId, "Saved destination", destId);
                } else if (path.equals("/admin/user")) {
                    long targetUserId = number(body, "id", 1, Long.MAX_VALUE);
                    if (targetUserId == currentUserId) {
                        throw new IllegalArgumentException("You cannot change your own access here.");
                    }
                    List<Map<String, Object>> targetRows = query(
                        connection,
                        "SELECT role FROM users WHERE id=?",
                        targetUserId
                    );
                    if (targetRows.isEmpty()) {
                        throw new Missing();
                    }
                    Map<String, Object> target = targetRows.get(0);
                    if ("ADMIN".equals(target.get("role"))) {
                        throw new IllegalArgumentException("Administrator accounts must be managed by the operator.");
                    }
                    boolean activeStatus = Boolean.TRUE.equals(body.get("active"));
                    execute(
                        connection,
                        "UPDATE users SET active=?,auth_version=auth_version+1 WHERE id=?",
                        activeStatus,
                        targetUserId
                    );
                    audit(connection, currentUserId, "Changed user access", targetUserId);
                } else if (path.equals("/admin/settings")) {
                    Map<String, Object> emailCheckMap = new HashMap<>();
                    emailCheckMap.put("email", text(body, "support_email", 254));
                    email(emailCheckMap);

                    String announcement = text(body, "announcement", 2000);
                    String supportEmail = text(body, "support_email", 254);
                    String registrationEnabled = choice(body, "registration_enabled", "true", "false");

                    execute(
                        connection,
                        "UPDATE settings SET setting_value=? WHERE setting_key='announcement'",
                        announcement
                    );
                    execute(
                        connection,
                        "UPDATE settings SET setting_value=? WHERE setting_key='support_email'",
                        supportEmail
                    );
                    execute(
                        connection,
                        "UPDATE settings SET setting_value=? WHERE setting_key='registration_enabled'",
                        registrationEnabled
                    );
                    audit(connection, currentUserId, "Updated experience settings", 0);
                } else if (path.equals("/admin/feedback")) {
                    long feedbackId = number(body, "id", 1, Long.MAX_VALUE);
                    String responseText = text(body, "response", 2000);
                    int updated = execute(
                        connection,
                        "UPDATE feedback SET response=? WHERE id=?",
                        responseText,
                        feedbackId
                    );
                    if (updated == 0) {
                        throw new Missing();
                    }
                    audit(connection, currentUserId, "Responded to feedback", feedbackId);
                } else {
                    throw new Missing();
                }

                connection.commit();
                if (Boolean.TRUE.equals(output.get("sign_in_again"))) {
                    session.invalidate();
                }
                write(response, output);
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Missing e) {
            error(response, 404, "This item was not found.");
        } catch (IllegalArgumentException e) {
            error(response, 400, e.getMessage());
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                error(response, 409, "This record already exists or conflicts with related data.");
            } else {
                getServletContext().log("Database request failed", e);
                error(response, 503, "The database is temporarily unavailable. Please try again.");
            }
        } catch (Exception e) {
            getServletContext().log("Request failed", e);
            error(response, 500, "We could not complete that request. Please try again.");
        }
    }

    // Handles user login and new account registration
    private void authenticate(
        Connection connection,
        DatabaseSession session,
        HttpServletResponse response,
        Map<String, Object> body,
        String path
    ) throws Exception {
        String email = email(body);
        String password = password(body, "password");
        String key = email;
        long now = System.currentTimeMillis();

        connection.setAutoCommit(false);
        try {
            execute(connection, "DELETE FROM login_attempts WHERE window_start<?", now - 900000);
            List<Map<String, Object>> attempts = query(
                connection,
                "SELECT attempts FROM login_attempts WHERE attempt_key=? FOR UPDATE",
                key
            );

            if (!attempts.isEmpty()) {
                int count = ((Number) attempts.get(0).get("attempts")).intValue();
                if (count >= 8) {
                    connection.commit();
                    response.setHeader("Retry-After", "900");
                    error(response, 429, "Too many attempts. Try again in 15 minutes.");
                    return;
                }
            }

            if (attempts.isEmpty()) {
                execute(
                    connection,
                    "INSERT INTO login_attempts VALUES (?,1,?)",
                    key,
                    now
                );
            } else {
                execute(
                    connection,
                    "UPDATE login_attempts SET attempts=attempts+1 WHERE attempt_key=?",
                    key
                );
            }
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }

        if (path.equals("/register")) {
            Map<String, String> currentSettings = settings(connection);
            if (!"true".equals(currentSettings.get("registration_enabled"))) {
                throw new IllegalArgumentException("Registration is currently closed.");
            }
            Security.validatePassword(password);
            execute(
                connection,
                "INSERT INTO users(name,email,password) VALUES (?,?,?)",
                text(body, "name", 100),
                email,
                Security.hash(password)
            );
        }

        List<Map<String, Object>> rows = query(
            connection,
            "SELECT * FROM users WHERE email=?",
            email
        );

        String hashToVerify = DUMMY_PASSWORD_HASH;
        if (!rows.isEmpty()) {
            hashToVerify = rows.get(0).get("password").toString();
        }

        boolean valid = Security.verify(password, hashToVerify);
        if (!valid || rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("active"))) {
            error(response, 401, "Email or password is incorrect.");
            return;
        }

        execute(connection, "DELETE FROM login_attempts WHERE attempt_key=?", key);

        long userId = ((Number) rows.get(0).get("id")).longValue();
        int authVersion = ((Number) rows.get(0).get("auth_version")).intValue();
        session.authenticate(userId, authVersion);

        Map<String, Object> okResponse = new HashMap<>();
        okResponse.put("ok", true);
        write(response, okResponse);
    }

    // Fetches all user dashboard data: destinations, trips, wishlist, memories, feedback, settings
    private Map<String, Object> data(Connection connection, long userId) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
            "destinations",
            query(connection, "SELECT * FROM destinations WHERE active=TRUE ORDER BY name")
        );
        result.put(
            "trips",
            query(
                connection,
                "SELECT t.*,d.name AS destination,d.image,d.season,COALESCE((SELECT SUM(amount) FROM expenses e WHERE e.trip_id=t.id),0) AS estimated_total FROM trips t JOIN destinations d ON d.id=t.destination_id WHERE t.user_id=? ORDER BY t.start_date DESC",
                userId
            )
        );
        result.put(
            "wishlist",
            query(
                connection,
                "SELECT d.* FROM wishlist w JOIN destinations d ON d.id=w.destination_id WHERE w.user_id=? ORDER BY d.name",
                userId
            )
        );
        result.put(
            "memories",
            query(
                connection,
                "SELECT m.id,m.trip_id,m.title,m.memory_date,m.note,CASE WHEN m.photo IS NULL THEN FALSE ELSE TRUE END AS has_photo,t.title AS trip_title FROM memories m JOIN trips t ON t.id=m.trip_id WHERE m.user_id=? ORDER BY m.memory_date DESC",
                userId
            )
        );
        result.put(
            "feedback",
            query(
                connection,
                "SELECT f.*,t.title FROM feedback f JOIN trips t ON t.id=f.trip_id WHERE f.user_id=? ORDER BY f.id DESC",
                userId
            )
        );
        result.put("settings", settings(connection));
        return result;
    }

    // Fetches admin panel management data
    private Map<String, Object> adminData(Connection connection, HttpServletRequest request)
        throws SQLException {
        String search = "";
        if (request.getParameter("q") != null) {
            search = request.getParameter("q").trim();
        }
        if (search.length() > 100) {
            throw new IllegalArgumentException("Search must be at most 100 characters.");
        }

        int usersPage = page(request, "user_page");
        int feedbackPage = page(request, "feedback_page");
        String matchPattern = "%" + search + "%";

        List<Map<String, Object>> userCountRows = query(
            connection,
            "SELECT COUNT(*) AS n FROM users WHERE name LIKE ? OR email LIKE ?",
            matchPattern,
            matchPattern
        );
        long totalUsers = ((Number) userCountRows.get(0).get("n")).longValue();

        List<Map<String, Object>> feedbackCountRows = query(
            connection,
            "SELECT COUNT(*) AS n FROM feedback"
        );
        long totalFeedback = ((Number) feedbackCountRows.get(0).get("n")).longValue();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(
            "users",
            query(
                connection,
                "SELECT id,name,email,role,active,created_at FROM users WHERE name LIKE ? OR email LIKE ? ORDER BY id DESC LIMIT 50 OFFSET ?",
                matchPattern,
                matchPattern,
                (usersPage - 1) * 50
            )
        );
        out.put(
            "feedback",
            query(
                connection,
                "SELECT f.*,u.name,t.title FROM feedback f JOIN users u ON u.id=f.user_id JOIN trips t ON t.id=f.trip_id ORDER BY f.id DESC LIMIT 50 OFFSET ?",
                (feedbackPage - 1) * 50
            )
        );
        out.put("destinations", query(connection, "SELECT * FROM destinations ORDER BY name"));
        out.put(
            "audit",
            query(
                connection,
                "SELECT a.*,u.name FROM audit_log a JOIN users u ON u.id=a.actor_id ORDER BY a.id DESC LIMIT 100"
            )
        );

        List<Map<String, Object>> statsRows = query(
            connection,
            "SELECT (SELECT COUNT(*) FROM users) AS users,(SELECT COUNT(*) FROM trips) AS trips,(SELECT COUNT(*) FROM destinations WHERE active=TRUE) AS destinations,(SELECT AVG(rating) FROM feedback) AS rating"
        );
        out.put("stats", statsRows.get(0));
        out.put("settings", settings(connection));

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("user_page", usersPage);
        pagination.put("feedback_page", feedbackPage);
        pagination.put("user_total", totalUsers);
        pagination.put("feedback_total", totalFeedback);
        pagination.put("q", search);
        out.put("pagination", pagination);

        return out;
    }

    // Helper to parse page number from query parameters
    private static int page(HttpServletRequest request, String key) {
        String value = request.getParameter(key);
        if (value == null) {
            return 1;
        }
        try {
            int pageNum = Integer.parseInt(value);
            if (pageNum < 1 || pageNum > 100000) {
                throw new NumberFormatException();
            }
            return pageNum;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid page number.");
        }
    }

    // Retrieves site settings key-value map
    private static Map<String, String> settings(Connection connection) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        List<Map<String, Object>> settingRows = query(connection, "SELECT * FROM settings");
        for (int i = 0; i < settingRows.size(); i++) {
            Map<String, Object> row = settingRows.get(i);
            out.put(row.get("setting_key").toString(), row.get("setting_value").toString());
        }
        return out;
    }

    // Validates and normalizes email input
    private static String email(Map<String, Object> body) {
        String s = text(body, "email", 254).toLowerCase(Locale.ROOT);
        if (!s.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        return s;
    }

    // Parses a positive long ID from string
    private static long parseId(String str) {
        try {
            long id = Long.parseLong(str);
            if (id < 1) {
                throw new NumberFormatException();
            }
            return id;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid identifier.");
        }
    }

    // Logs an administrative action
    private static void audit(Connection connection, long userId, String action, long targetId)
        throws SQLException {
        execute(
            connection,
            "INSERT INTO audit_log(actor_id,action,target_id) VALUES (?,?,?)",
            userId,
            action,
            targetId
        );
    }

    // Validates, resizes and decodes an uploaded base64 photo
    static byte[] photo(Map<String, Object> body) throws IOException {
        String raw = optional(body, "photo", 4100000);
        if (raw.trim().isEmpty()) {
            return null;
        }
        if (!raw.matches("data:image/(jpeg|jpg|png|webp);base64,[A-Za-z0-9+/=\\r\\n]+")) {
            throw new IllegalArgumentException("Upload a JPEG, PNG or WebP photograph.");
        }

        int commaIndex = raw.indexOf(',');
        byte[] bytes = Base64.getDecoder().decode(raw.substring(commaIndex + 1));
        if (bytes.length > 4000000) {
            throw new IllegalArgumentException("Photos must be under 4 MB.");
        }

        ByteArrayInputStream byteStream = new ByteArrayInputStream(bytes);
        try (ImageInputStream stream = ImageIO.createImageInputStream(byteStream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("That photo format cannot be read. Use JPEG, PNG or WebP.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 20000000) {
                    throw new IllegalArgumentException("Photos must contain at most 20 million pixels.");
                }

                BufferedImage inputImage = reader.read(0);
                int maxDimension = Math.max(width, height);
                double scale = Math.min(1.0, 1600.0 / maxDimension);

                int scaledWidth = Math.max(1, (int) (width * scale));
                int scaledHeight = Math.max(1, (int) (height * scale));

                BufferedImage cleanImage = new BufferedImage(
                    scaledWidth,
                    scaledHeight,
                    BufferedImage.TYPE_INT_RGB
                );
                Graphics2D graphics = cleanImage.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, cleanImage.getWidth(), cleanImage.getHeight());
                    graphics.drawImage(inputImage, 0, 0, cleanImage.getWidth(), cleanImage.getHeight(), null);
                } finally {
                    graphics.dispose();
                }

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                ImageIO.write(cleanImage, "jpg", outputStream);
                return outputStream.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                "That photo is damaged or incomplete. Use a valid JPEG, PNG or WebP."
            );
        }
    }

    // Serializes object as JSON and writes to response
    private static void write(HttpServletResponse response, Object obj) throws IOException {
        JSON.writeValue(response.getOutputStream(), obj);
    }

    // Sends a JSON error message with HTTP status code
    private static void error(HttpServletResponse response, int status, String message)
        throws IOException {
        response.setStatus(status);
        Map<String, Object> errorMap = new HashMap<>();
        errorMap.put("error", message);
        write(response, errorMap);
    }
}
