package com.tripplanner;

import static com.tripplanner.Database.*;
import static com.tripplanner.TripService.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;

/** MVC HTTP boundary. All writes are POST, CSRF checked and transactional. */
public final class ApiController extends HttpServlet {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DUMMY_PASSWORD_HASH = Security.hash(
        "dummy-password-for-timing-only"
    );
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private Database db() {
        return (Database) getServletContext().getAttribute("db");
    }

    protected void service(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
        request.setCharacterEncoding("UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        String path = Objects.toString(request.getPathInfo(), "");
        if (!Set.of("GET", "POST").contains(request.getMethod())) {
            error(response, 405, "Method not allowed.");
            return;
        }
        try (Connection connection = db().connection()) {
            if (path.equals("/health") && request.getMethod().equals("GET")) {
                query(connection, "SELECT 1");
                write(response, Map.of("status", "ok"));
                return;
            }
            DatabaseSession session = DatabaseSession.load(connection, request, response);
            Map<String, Object> user = null;
            if (session.getAttribute("uid") instanceof Number userId) {
                List<Map<String, Object>> rows = query(
                    connection,
                    "SELECT id,name,email,role,auth_version FROM users WHERE id=? AND active=TRUE",
                    userId.longValue()
                );
                if (
                    !rows.isEmpty() &&
                    Objects.equals(rows.get(0).get("auth_version"), session.getAttribute("version"))
                ) user = rows.get(0);
                else {
                    session.invalidate();
                    error(response, 401, "Please sign in again.");
                    return;
                }
            }
            if (path.equals("/package-photo") && request.getMethod().equals("GET")) {
                long id = parseId(request.getParameter("id"));
                List<Map<String, Object>> rows = query(
                    connection,
                    "SELECT photo FROM package_photos WHERE package_id=?",
                    id
                );
                if (rows.isEmpty() || rows.get(0).get("photo") == null) throw new Missing();
                Object raw = rows.get(0).get("photo");
                byte[] photo =
                    raw instanceof Blob blob ? blob.getBytes(1, (int) blob.length()) : (byte[]) raw;
                response.setCharacterEncoding(null);
                response.setContentType("image/jpeg");
                response.setHeader("Cache-Control", "public, max-age=86400");
                response.setContentLength(photo.length);
                response.getOutputStream().write(photo);
                return;
            }
            if (path.equals("/session") && request.getMethod().equals("GET")) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("csrf", session.getAttribute("csrf"));
                out.put("user", user);
                out.put("settings", settings(connection));
                write(response, out);
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            if (request.getMethod().equals("POST")) {
                if (
                    !Objects.equals(session.getAttribute("csrf"), request.getHeader("X-CSRF-Token"))
                ) {
                    error(response, 403, "Your session changed. Refresh and try again.");
                    return;
                }
                if (
                    request.getContentType() == null ||
                    !request.getContentType().startsWith("application/json")
                ) {
                    error(response, 415, "JSON content is required.");
                    return;
                }
                byte[] bytes = request.getInputStream().readNBytes(4_400_001);
                if (bytes.length > 4_400_000) {
                    error(response, 413, "Upload is too large.");
                    return;
                }
                try {
                    body = JSON.readValue(bytes, MAP);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid JSON request.");
                }
                if (body == null) throw new IllegalArgumentException("A JSON object is required.");
                if (path.equals("/login") || path.equals("/register")) {
                    authenticate(connection, session, response, body, path);
                    return;
                }
                if (path.equals("/logout")) {
                    session.invalidate();
                    write(response, Map.of("ok", true));
                    return;
                }
            }
            if (user == null) {
                error(response, 401, "Please sign in to continue.");
                return;
            }
            long userId = ((Number) user.get("id")).longValue();
            if (path.startsWith("/admin") && !user.get("role").equals("ADMIN")) {
                error(response, 403, "Administrator access is required.");
                return;
            }
            if (request.getMethod().equals("GET")) {
                if (path.equals("/packages")) {
                    write(response, PackageService.overview(connection, userId, false));
                    return;
                }
                if (path.equals("/admin/packages")) {
                    write(response, PackageService.overview(connection, userId, true));
                    return;
                }
                if (path.equals("/destination-photo")) {
                    long id = parseId(request.getParameter("id"));
                    var rows = query(
                        connection,
                        "SELECT p.photo FROM destination_photos p JOIN destinations d ON d.id=p.destination_id WHERE p.destination_id=? AND (d.active=TRUE OR ?=TRUE)",
                        id,
                        "ADMIN".equals(user.get("role"))
                    );
                    if (rows.isEmpty()) throw new Missing();
                    Object raw = rows.get(0).get("photo");
                    byte[] bytes =
                        raw instanceof Blob blob
                            ? blob.getBytes(1, (int) blob.length())
                            : (byte[]) raw;
                    response.setCharacterEncoding(null);
                    response.setContentType("image/jpeg");
                    response.setContentLength(bytes.length);
                    response.getOutputStream().write(bytes);
                    return;
                }
                if (path.equals("/data")) {
                    write(response, data(connection, userId));
                    return;
                }
                if (path.equals("/trip")) {
                    long id = parseId(request.getParameter("id"));
                    Map<String, Object> t = owned(connection, userId, id);
                    t.put(
                        "itinerary",
                        query(
                            connection,
                            "SELECT * FROM itinerary WHERE trip_id=? ORDER BY day_number,time_slot,id",
                            id
                        )
                    );
                    t.put(
                        "expenses",
                        query(connection, "SELECT * FROM expenses WHERE trip_id=? ORDER BY id", id)
                    );
                    t.put(
                        "packing",
                        query(connection, "SELECT * FROM packing WHERE trip_id=? ORDER BY id", id)
                    );
                    write(response, t);
                    return;
                }
                if (path.equals("/photo")) {
                    List<Map<String, Object>> rows = query(
                        connection,
                        "SELECT photo FROM memories WHERE id=? AND user_id=?",
                        parseId(request.getParameter("id")),
                        userId
                    );
                    if (rows.isEmpty() || rows.get(0).get("photo") == null) throw new Missing();
                    Object raw = rows.get(0).get("photo");
                    byte[] photo =
                        raw instanceof Blob blob
                            ? blob.getBytes(1, (int) blob.length())
                            : (byte[]) raw;
                    response.setCharacterEncoding(null);
                    response.setContentType("image/jpeg");
                    response.setContentLength(photo.length);
                    response.getOutputStream().write(photo);
                    return;
                }
                if (path.equals("/admin")) {
                    write(response, adminData(connection, request));
                    return;
                }
                throw new Missing();
            }
            connection.setAutoCommit(false);
            try {
                // Serialize one user's writes so quota checks and idempotent saves cannot race.
                query(connection, "SELECT id FROM users WHERE id=? FOR UPDATE", userId);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                switch (path) {
                    case "/admin/package" -> out.put(
                        "id",
                        PackageService.save(connection, userId, body)
                    );
                    case "/admin/hotel" -> out.put(
                        "id",
                        PackageService.supplier(connection, userId, body, true)
                    );
                    case "/admin/transport" -> out.put(
                        "id",
                        PackageService.supplier(connection, userId, body, false)
                    );
                    case "/admin/payment" -> out.put(
                        "id",
                        PackageService.payment(connection, userId, body)
                    );
                    case "/admin/booking-status" -> PackageService.status(
                        connection,
                        userId,
                        body,
                        true
                    );
                    case "/booking" -> {
                        if (!user.get("role").equals("CLIENT")) throw new IllegalArgumentException(
                            "Use a traveler account to request a package."
                        );
                        out.put("id", PackageService.book(connection, userId, body));
                    }
                    case "/booking-status" -> PackageService.status(
                        connection,
                        userId,
                        body,
                        false
                    );
                    case "/booking-demo-payment" -> out.put(
                        "id",
                        PackageService.demoPayment(connection, userId, body)
                    );
                    case "/trip" -> out.put("id", saveTrip(connection, userId, body));
                    case "/trip-status" -> {
                        long id = number(body, "id", 1, Long.MAX_VALUE);
                        Map<String, Object> t = owned(connection, userId, id);
                        editable(t);
                        String status = choice(body, "status", "CANCELLED", "COMPLETED");
                        if (
                            status.equals("COMPLETED") &&
                            LocalDate.parse(t.get("end_date").toString()).isAfter(LocalDate.now())
                        ) throw new IllegalArgumentException(
                            "A trip can be completed on or after its end date."
                        );
                        execute(connection, "UPDATE trips SET status=? WHERE id=?", status, id);
                    }
                    case "/expenses", "/itinerary", "/packing" -> child(
                        connection,
                        userId,
                        path.substring(1),
                        body
                    );
                    case "/wishlist" -> {
                        long id = number(body, "destination_id", 1, Long.MAX_VALUE);
                        if (Boolean.TRUE.equals(body.get("saved"))) {
                            if (
                                query(
                                    connection,
                                    "SELECT id FROM destinations WHERE id=? AND active=TRUE",
                                    id
                                ).isEmpty()
                            ) throw new Missing();
                            if (
                                query(
                                    connection,
                                    "SELECT user_id FROM wishlist WHERE user_id=? AND destination_id=?",
                                    userId,
                                    id
                                ).isEmpty()
                            ) execute(connection, "INSERT INTO wishlist VALUES (?,?)", userId, id);
                        } else execute(
                            connection,
                            "DELETE FROM wishlist WHERE user_id=? AND destination_id=?",
                            userId,
                            id
                        );
                    }
                    case "/memory" -> {
                        if ("delete".equals(body.get("action"))) {
                            if (
                                execute(
                                    connection,
                                    "DELETE FROM memories WHERE id=? AND user_id=?",
                                    number(body, "id", 1, Long.MAX_VALUE),
                                    userId
                                ) == 0
                            ) throw new Missing();
                        } else {
                            long trip = number(body, "trip_id", 1, Long.MAX_VALUE);
                            Map<String, Object> t = owned(connection, userId, trip);
                            completed(t);
                            LocalDate date = date(body, "memory_date");
                            if (
                                date.isBefore(LocalDate.parse(t.get("start_date").toString())) ||
                                date.isAfter(LocalDate.parse(t.get("end_date").toString()))
                            ) throw new IllegalArgumentException(
                                "Memory date must be within the trip dates."
                            );
                            if (
                                (
                                    (Number) query(
                                        connection,
                                        "SELECT COUNT(*) AS n FROM memories WHERE user_id=?",
                                        userId
                                    )
                                        .get(0)
                                        .get("n")
                                ).longValue() >= 200
                            ) throw new IllegalArgumentException(
                                "Your gallery has reached its 200-memory limit."
                            );
                            execute(
                                connection,
                                "INSERT INTO memories(user_id,trip_id,title,memory_date,note,photo) VALUES (?,?,?,?,?,?)",
                                userId,
                                trip,
                                text(body, "title", 150),
                                date,
                                optional(body, "note", 2000),
                                photo(body)
                            );
                        }
                    }
                    case "/feedback" -> {
                        long trip = number(body, "trip_id", 1, Long.MAX_VALUE);
                        completed(owned(connection, userId, trip));
                        execute(
                            connection,
                            "INSERT INTO feedback(user_id,trip_id,rating,message) VALUES (?,?,?,?)",
                            userId,
                            trip,
                            number(body, "rating", 1, 5),
                            text(body, "message", 2000)
                        );
                    }
                    case "/profile" -> {
                        String name = text(body, "name", 100),
                            email = email(body);
                        execute(
                            connection,
                            "UPDATE users SET name=?,email=? WHERE id=?",
                            name,
                            email,
                            userId
                        );
                    }
                    case "/password" -> {
                        String old = password(body, "current_password"),
                            password = password(body, "password");
                        Security.validatePassword(password);
                        Map<String, Object> row = query(
                            connection,
                            "SELECT password FROM users WHERE id=?",
                            userId
                        ).get(0);
                        if (
                            !Security.verify(old, row.get("password").toString())
                        ) throw new IllegalArgumentException("Current password is incorrect.");
                        execute(
                            connection,
                            "UPDATE users SET password=?,auth_version=auth_version+1 WHERE id=?",
                            Security.hash(password),
                            userId
                        );
                        out.put("sign_in_again", true);
                    }
                    case "/admin/destination" -> {
                        long id = body.containsKey("id")
                            ? number(body, "id", 1, Long.MAX_VALUE)
                            : 0;
                        String name = text(body, "name", 120),
                            region = text(body, "region", 120),
                            description = text(body, "description", 4000),
                            season = choice(
                                body,
                                "season",
                                "Summer",
                                "Monsoon",
                                "Autumn",
                                "Winter"
                            ),
                            category = text(body, "category", 40),
                            activities = listText(body, "activities", 1000),
                            attractions = listText(body, "attractions", 1000),
                            img =
                                id == 0
                                    ? "mountains.jpg"
                                    : query(
                                          connection,
                                          "SELECT image FROM destinations WHERE id=? FOR UPDATE",
                                          id
                                      )
                                          .stream()
                                          .findFirst()
                                          .orElseThrow(Missing::new)
                                          .get("image")
                                          .toString();
                        byte[] uploadedPhoto = body.containsKey("photo") ? photo(body) : null;
                        if (
                            body.containsKey("image") &&
                            uploadedPhoto == null &&
                            !img.startsWith("api/")
                        ) {
                            img = choice(body, "image", "mountains.jpg", "coast.jpg", "city.jpg");
                        }
                        Object[] args = {
                            name,
                            region,
                            description,
                            season,
                            category,
                            money(body, "daily_cost"),
                            number(body, "duration", 1, 60),
                            activities,
                            attractions,
                            img,
                            Boolean.TRUE.equals(body.get("active")),
                        };
                        if (id == 0) id = insert(
                            connection,
                            "INSERT INTO destinations(name,region,description,season,category,daily_cost,duration,activities,attractions,image,active) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                            args
                        );
                        else {
                            List<Object> a = new ArrayList<>(Arrays.asList(args));
                            a.add(id);
                            if (
                                execute(
                                    connection,
                                    "UPDATE destinations SET name=?,region=?,description=?,season=?,category=?,daily_cost=?,duration=?,activities=?,attractions=?,image=?,active=? WHERE id=?",
                                    a.toArray()
                                ) == 0
                            ) throw new Missing();
                        }
                        if (uploadedPhoto != null) {
                            execute(
                                connection,
                                "DELETE FROM destination_photos WHERE destination_id=?",
                                id
                            );
                            execute(
                                connection,
                                "INSERT INTO destination_photos(destination_id,photo) VALUES (?,?)",
                                id,
                                uploadedPhoto
                            );
                            execute(
                                connection,
                                "UPDATE destinations SET image=? WHERE id=?",
                                "api/destination-photo?id=" + id,
                                id
                            );
                        }
                        out.put("id", id);
                        audit(connection, userId, "Saved destination", id);
                    }
                    case "/admin/user" -> {
                        long id = number(body, "id", 1, Long.MAX_VALUE);
                        if (id == userId) throw new IllegalArgumentException(
                            "You cannot change your own access here."
                        );
                        Map<String, Object> target = query(
                            connection,
                            "SELECT role FROM users WHERE id=?",
                            id
                        )
                            .stream()
                            .findFirst()
                            .orElseThrow(Missing::new);
                        if (target.get("role").equals("ADMIN")) throw new IllegalArgumentException(
                            "Administrator accounts must be managed by the operator."
                        );
                        execute(
                            connection,
                            "UPDATE users SET active=?,auth_version=auth_version+1 WHERE id=?",
                            Boolean.TRUE.equals(body.get("active")),
                            id
                        );
                        audit(connection, userId, "Changed user access", id);
                    }
                    case "/admin/settings" -> {
                        email(Map.of("email", text(body, "support_email", 254)));
                        for (String key : List.of(
                            "announcement",
                            "support_email",
                            "registration_enabled"
                        )) {
                            String val = key.equals("registration_enabled")
                                ? choice(body, key, "true", "false")
                                : text(body, key, key.equals("announcement") ? 2000 : 254);
                            execute(
                                connection,
                                "UPDATE settings SET setting_value=? WHERE setting_key=?",
                                val,
                                key
                            );
                        }
                        audit(connection, userId, "Updated experience settings", 0);
                    }
                    case "/admin/feedback" -> {
                        long id = number(body, "id", 1, Long.MAX_VALUE);
                        if (
                            execute(
                                connection,
                                "UPDATE feedback SET response=? WHERE id=?",
                                text(body, "response", 2000),
                                id
                            ) == 0
                        ) throw new Missing();
                        audit(connection, userId, "Responded to feedback", id);
                    }
                    default -> throw new Missing();
                }
                connection.commit();
                if (Boolean.TRUE.equals(out.get("sign_in_again"))) session.invalidate();
                write(response, out);
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
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) error(
                response,
                409,
                "This record already exists or conflicts with related data."
            );
            else {
                getServletContext().log("Database request failed", e);
                error(response, 503, "The database is temporarily unavailable. Please try again.");
            }
        } catch (Exception e) {
            getServletContext().log("Request failed", e);
            error(response, 500, "We could not complete that request. Please try again.");
        }
    }

    private void authenticate(
        Connection connection,
        DatabaseSession session,
        HttpServletResponse response,
        Map<String, Object> body,
        String path
    ) throws Exception {
        String email = email(body),
            password = password(body, "password");
        String key = email;
        long now = System.currentTimeMillis();
        // Persisted account throttle works across restarts and application instances.
        connection.setAutoCommit(false);
        try {
            execute(connection, "DELETE FROM login_attempts WHERE window_start<?", now - 900000);
            List<Map<String, Object>> attempts = query(
                connection,
                "SELECT attempts FROM login_attempts WHERE attempt_key=? FOR UPDATE",
                key
            );
            if (!attempts.isEmpty() && ((Number) attempts.get(0).get("attempts")).intValue() >= 8) {
                connection.commit();
                response.setHeader("Retry-After", "900");
                error(response, 429, "Too many attempts. Try again in 15 minutes.");
                return;
            }
            if (attempts.isEmpty()) execute(
                connection,
                "INSERT INTO login_attempts VALUES (?,1,?)",
                key,
                now
            );
            else execute(
                connection,
                "UPDATE login_attempts SET attempts=attempts+1 WHERE attempt_key=?",
                key
            );
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
        if (path.equals("/register")) {
            if (
                !"true".equals(settings(connection).get("registration_enabled"))
            ) throw new IllegalArgumentException("Registration is currently closed.");
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
        boolean valid = Security.verify(
            password,
            rows.isEmpty() ? DUMMY_PASSWORD_HASH : rows.get(0).get("password").toString()
        );
        if (!valid || rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("active"))) {
            error(response, 401, "Email or password is incorrect.");
            return;
        }
        execute(connection, "DELETE FROM login_attempts WHERE attempt_key=?", key);
        session.authenticate(
            ((Number) rows.get(0).get("id")).longValue(),
            ((Number) rows.get(0).get("auth_version")).intValue()
        );
        write(response, Map.of("ok", true));
    }

    private Map<String, Object> data(Connection connection, long userId) throws SQLException {
        return Map.of(
            "destinations",
            query(connection, "SELECT * FROM destinations WHERE active=TRUE ORDER BY name"),
            "trips",
            query(
                connection,
                "SELECT t.*,d.name AS destination,d.image,d.season,COALESCE((SELECT SUM(amount) FROM expenses e WHERE e.trip_id=t.id),0) AS estimated_total FROM trips t JOIN destinations d ON d.id=t.destination_id WHERE t.user_id=? ORDER BY t.start_date DESC",
                userId
            ),
            "wishlist",
            query(
                connection,
                "SELECT d.* FROM wishlist w JOIN destinations d ON d.id=w.destination_id WHERE w.user_id=? ORDER BY d.name",
                userId
            ),
            "memories",
            query(
                connection,
                "SELECT m.id,m.trip_id,m.title,m.memory_date,m.note,CASE WHEN m.photo IS NULL THEN FALSE ELSE TRUE END AS has_photo,t.title AS trip_title FROM memories m JOIN trips t ON t.id=m.trip_id WHERE m.user_id=? ORDER BY m.memory_date DESC",
                userId
            ),
            "feedback",
            query(
                connection,
                "SELECT f.*,t.title FROM feedback f JOIN trips t ON t.id=f.trip_id WHERE f.user_id=? ORDER BY f.id DESC",
                userId
            ),
            "settings",
            settings(connection)
        );
    }

    private Map<String, Object> adminData(Connection connection, HttpServletRequest request)
        throws SQLException {
        String search = Objects.toString(request.getParameter("q"), "").trim();
        if (search.length() > 100) throw new IllegalArgumentException(
            "Search must be at most 100 characters."
        );
        int usersPage = page(request, "user_page"),
            feedbackPage = page(request, "feedback_page");
        String match = "%" + search + "%";
        long users = (
            (Number) query(
                connection,
                "SELECT COUNT(*) AS n FROM users WHERE name LIKE ? OR email LIKE ?",
                match,
                match
            )
                .get(0)
                .get("n")
        ).longValue();
        long feedback = (
            (Number) query(connection, "SELECT COUNT(*) AS n FROM feedback").get(0).get("n")
        ).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(
            "users",
            query(
                connection,
                "SELECT id,name,email,role,active,created_at FROM users WHERE name LIKE ? OR email LIKE ? ORDER BY id DESC LIMIT 50 OFFSET ?",
                match,
                match,
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
        out.put(
            "stats",
            query(
                connection,
                "SELECT (SELECT COUNT(*) FROM users) AS users,(SELECT COUNT(*) FROM trips) AS trips,(SELECT COUNT(*) FROM destinations WHERE active=TRUE) AS destinations,(SELECT AVG(rating) FROM feedback) AS rating"
            ).get(0)
        );
        out.put("settings", settings(connection));
        out.put(
            "pagination",
            Map.of(
                "user_page",
                usersPage,
                "feedback_page",
                feedbackPage,
                "user_total",
                users,
                "feedback_total",
                feedback,
                "q",
                search
            )
        );
        return out;
    }

    private static int page(HttpServletRequest request, String key) {
        String value = request.getParameter(key);
        if (value == null) return 1;
        try {
            int page = Integer.parseInt(value);
            if (page < 1 || page > 100000) throw new NumberFormatException();
            return page;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid page number.");
        }
    }

    private static Map<String, String> settings(Connection connection) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> row : query(connection, "SELECT * FROM settings"))
            out.put(row.get("setting_key").toString(), row.get("setting_value").toString());
        return out;
    }

    private static String email(Map<String, Object> body) {
        String s = text(body, "email", 254).toLowerCase(Locale.ROOT);
        if (!s.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException(
            "Enter a valid email address."
        );
        return s;
    }

    private static long parseId(String s) {
        try {
            long id = Long.parseLong(s);
            if (id < 1) throw new NumberFormatException();
            return id;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid identifier.");
        }
    }

    private static void audit(Connection connection, long user, String action, long target)
        throws SQLException {
        execute(
            connection,
            "INSERT INTO audit_log(actor_id,action,target_id) VALUES (?,?,?)",
            user,
            action,
            target
        );
    }

    static byte[] photo(Map<String, Object> body) throws IOException {
        String raw = optional(body, "photo", 4_100_000);
        if (raw.isBlank()) return null;
        if (
            !raw.matches("data:image/(jpeg|jpg|png|webp);base64,[A-Za-z0-9+/=\\r\\n]+")
        ) throw new IllegalArgumentException("Upload a JPEG, PNG or WebP photograph.");
        byte[] bytes = Base64.getDecoder().decode(raw.substring(raw.indexOf(',') + 1));
        if (bytes.length > 4_000_000) throw new IllegalArgumentException(
            "Photos must be under 4 MB."
        );
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException(
                "That photo format cannot be read. Use JPEG, PNG or WebP."
            );
            var reader = readers.next();
            try {
                reader.setInput(stream);
                int w = reader.getWidth(0),
                    h = reader.getHeight(0);
                if (w < 1 || h < 1 || (long) w * h > 20_000_000) throw new IllegalArgumentException(
                    "Photos must contain at most 20 million pixels."
                );
                BufferedImage input = reader.read(0);
                double scale = Math.min(1, 1600.0 / Math.max(w, h));
                BufferedImage clean = new BufferedImage(
                    Math.max(1, (int) (w * scale)),
                    Math.max(1, (int) (h * scale)),
                    BufferedImage.TYPE_INT_RGB
                );
                Graphics2D g = clean.createGraphics();
                try {
                    g.setColor(java.awt.Color.WHITE);
                    g.fillRect(0, 0, clean.getWidth(), clean.getHeight());
                    g.drawImage(input, 0, 0, clean.getWidth(), clean.getHeight(), null);
                } finally {
                    g.dispose();
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(clean, "jpg", out);
                return out.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                "That photo is damaged or incomplete. Use a valid JPEG, PNG or WebP."
            );
        }
    }

    private static void write(HttpServletResponse response, Object o) throws IOException {
        JSON.writeValue(response.getOutputStream(), o);
    }

    private static void error(HttpServletResponse response, int status, String message)
        throws IOException {
        response.setStatus(status);
        write(response, Map.of("error", message));
    }
}
