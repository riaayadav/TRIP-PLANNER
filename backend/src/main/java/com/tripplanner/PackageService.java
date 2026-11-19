package com.tripplanner;

import static com.tripplanner.Database.*;
import static com.tripplanner.TripService.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * PackageService manages travel package listings, hotel/transport suppliers, bookings, and payments.
 * 
 * Key Responsibilities:
 * 1. Package Catalog & Overview: Queries curated packages with joined destination, hotel, and transport data.
 * 2. Balance & Status Calculation: Dynamically computes paid amounts, outstanding balances, and payment statuses.
 * 3. Package Management (Admin): Creates, updates, publishes, or deletes package records.
 * 4. Supplier Management (Admin): Manages hotel accommodations and transportation routes.
 * 5. Booking Workflow (Traveler): Validates traveler limits, captures package snapshot, and creates bookings.
 * 6. Simulated Payment Ledger: Records demo receipts and refunds, enforcing double-entry financial integrity.
 */
public final class PackageService {

    private static final ObjectMapper JSON = new ObjectMapper();

    // Base SQL query joining packages with destinations, hotels, and transport options
    private static final String CATALOG_SQL =
        "SELECT p.*,d.name AS destination,d.season,h.name AS hotel,h.location AS hotel_location,h.description AS hotel_description,h.amenities,t.name AS transport,t.mode,t.route_details,t.description AS transport_description " +
        "FROM travel_packages p " +
        "JOIN destinations d ON d.id=p.destination_id " +
        "JOIN hotels h ON h.id=p.hotel_id " +
        "JOIN transport_options t ON t.id=p.transport_id";

    // =========================================================================
    // 1. PACKAGE CATALOG & OVERVIEW QUERIES
    // =========================================================================

    /**
     * Retrieves the package catalog list.
     * - Travelers: Only see active, non-deleted packages whose destination, hotel, and transport are active.
     * - Administrators: See all non-deleted packages (including drafts/inactive ones).
     */
    public static List<Map<String, Object>> catalog(Connection connection, boolean admin)
        throws SQLException {
        String filter = admin
            ? " WHERE p.deleted=FALSE"
            : " WHERE p.deleted=FALSE AND p.active=TRUE AND d.active=TRUE AND h.active=TRUE AND t.active=TRUE";
        return query(connection, CATALOG_SQL + filter + " ORDER BY p.id DESC");
    }

    /**
     * Compiles overview data for dashboard views:
     * - List of packages
     * - User bookings (all bookings for admin, or personal bookings for travelers)
     * - Payment history, total paid, remaining balance, and status (PAID, PARTIAL, UNPAID, REFUNDED)
     * - Admin reference lists (hotels, transport options, destinations)
     */
    public static Map<String, Object> overview(Connection connection, long userId, boolean admin)
        throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packages", catalog(connection, admin));

        // Query bookings
        String bookingSql = "SELECT b.*,u.name AS customer,u.email FROM package_bookings b JOIN users u ON u.id=b.user_id";
        List<Map<String, Object>> bookings;
        if (admin) {
            bookings = query(connection, bookingSql + " ORDER BY b.id DESC");
        } else {
            bookings = query(connection, bookingSql + " WHERE b.user_id=? ORDER BY b.id DESC", userId);
        }

        // Calculate paid totals and payment status for each booking
        for (Map<String, Object> booking : bookings) {
            Object bookingId = booking.get("id");
            List<Map<String, Object>> payments = query(
                connection,
                "SELECT id,kind,amount,reference,note,created_at FROM booking_payments WHERE booking_id=? ORDER BY id",
                bookingId
            );

            BigDecimal paid = BigDecimal.ZERO;
            BigDecimal received = BigDecimal.ZERO;

            for (Map<String, Object> payment : payments) {
                BigDecimal amount = new BigDecimal(payment.get("amount").toString());
                if ("RECEIPT".equals(payment.get("kind"))) {
                    paid = paid.add(amount);
                    received = received.add(amount);
                } else {
                    // REFUND decreases the net paid amount
                    paid = paid.subtract(amount);
                }
            }

            BigDecimal total = new BigDecimal(booking.get("total").toString());
            booking.put("paid", paid);
            booking.put("balance", total.subtract(paid));
            booking.put("payments", payments);

            // Determine textual payment status
            String paymentStatus;
            if (paid.signum() == 0) {
                if (received.signum() > 0) {
                    paymentStatus = "REFUNDED";
                } else {
                    paymentStatus = "UNPAID";
                }
            } else if (paid.compareTo(total) == 0) {
                paymentStatus = "PAID";
            } else {
                paymentStatus = "PARTIAL";
            }
            booking.put("payment_status", paymentStatus);
        }

        result.put("bookings", bookings);

        // Include admin dropdown reference options
        if (admin) {
            result.put("hotels", query(connection, "SELECT * FROM hotels ORDER BY name"));
            result.put("transport", query(connection, "SELECT * FROM transport_options ORDER BY name"));
            result.put("destinations", query(connection, "SELECT id,name,active FROM destinations ORDER BY name"));
        }

        return result;
    }

    /**
     * Helper to fetch a locked row from any table by ID.
     */
    private static Map<String, Object> row(Connection connection, String table, long id)
        throws SQLException {
        List<Map<String, Object>> rows = query(
            connection,
            "SELECT * FROM " + table + " WHERE id=? FOR UPDATE",
            id
        );
        if (rows.isEmpty()) {
            throw new Missing();
        }
        return rows.get(0);
    }

    /**
     * Helper to write an administrative audit entry into the audit_log table.
     */
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

    // =========================================================================
    // 2. SUPPLIER MANAGEMENT (Hotels & Transportation)
    // =========================================================================

    /**
     * Saves or updates a supplier (either a Hotel accommodation or a Transportation option).
     */
    public static long supplier(
        Connection connection,
        long userId,
        Map<String, Object> body,
        boolean isHotel
    ) throws SQLException {
        String table = isHotel ? "hotels" : "transport_options";
        String foreignKey = isHotel ? "hotel_id" : "transport_id";

        long id = 0;
        if (body.containsKey("id")) {
            id = number(body, "id", 1, Long.MAX_VALUE);
        }

        String action = Objects.toString(body.get("action"), "save");
        if (!action.equals("save") && !action.equals("delete")) {
            throw new IllegalArgumentException("Unknown action.");
        }

        if (id > 0) {
            row(connection, table, id);
        } else if (action.equals("delete")) {
            throw new Missing();
        }

        boolean active = Boolean.TRUE.equals(body.get("active")) && !action.equals("delete");

        // Prevent disabling a supplier if published packages are currently using it
        if (id > 0 && !active) {
            List<Map<String, Object>> linkedPackages = query(
                connection,
                "SELECT id FROM travel_packages WHERE " + foreignKey + "=? AND active=TRUE AND deleted=FALSE",
                id
            );
            if (!linkedPackages.isEmpty()) {
                throw new IllegalArgumentException("Unpublish linked packages before removing this supplier.");
            }
        }

        // Action: DELETE (soft-deletes by setting active=FALSE)
        if (action.equals("delete")) {
            execute(connection, "UPDATE " + table + " SET active=FALSE WHERE id=?", id);
            audit(connection, userId, "Archived " + table, id);
            return id;
        }

        String name = text(body, "name", 120);

        if (isHotel) {
            String location = text(body, "location", 200);
            String description = text(body, "description", 2000);
            String amenities = optional(body, "amenities", 2000);
            if (id == 0) {
                id = insert(
                    connection,
                    "INSERT INTO hotels(name,location,description,amenities,active) VALUES (?,?,?,?,?)",
                    name,
                    location,
                    description,
                    amenities,
                    active
                );
            } else {
                execute(
                    connection,
                    "UPDATE hotels SET name=?,location=?,description=?,amenities=?,active=? WHERE id=?",
                    name,
                    location,
                    description,
                    amenities,
                    active,
                    id
                );
            }
        } else {
            String mode = choice(body, "mode", "Flight", "Train", "Bus", "Car", "Ferry", "Mixed");
            String routeDetails = text(body, "route_details", 500);
            String description = text(body, "description", 2000);
            if (id == 0) {
                id = insert(
                    connection,
                    "INSERT INTO transport_options(name,mode,route_details,description,active) VALUES (?,?,?,?,?)",
                    name,
                    mode,
                    routeDetails,
                    description,
                    active
                );
            } else {
                execute(
                    connection,
                    "UPDATE transport_options SET name=?,mode=?,route_details=?,description=?,active=? WHERE id=?",
                    name,
                    mode,
                    routeDetails,
                    description,
                    active,
                    id
                );
            }
        }

        audit(connection, userId, "Saved " + table, id);
        return id;
    }

    // =========================================================================
    // 3. PACKAGE CREATION & UPDATES (Admin)
    // =========================================================================

    /**
     * Creates or updates a curated travel package.
     * Features:
     * - Optimistic Locking: Compares version numbers to prevent concurrent edits from overwriting each other.
     * - Auto-Resolves: Allows entering free-form destination, hotel, or transport names, creating them automatically.
     * - Photo Support: Decodes base64 images and saves them into package_photos.
     */
    public static long save(Connection connection, long userId, Map<String, Object> body)
        throws Exception {
        long packageId = 0;
        if (body.containsKey("id")) {
            packageId = number(body, "id", 1, Long.MAX_VALUE);
        }

        Map<String, Object> oldPackage = null;
        if (packageId > 0) {
            oldPackage = row(connection, "travel_packages", packageId);
            if (Boolean.TRUE.equals(oldPackage.get("deleted"))) {
                throw new Missing();
            }
            long newVersion = number(body, "version", 1, Integer.MAX_VALUE);
            long oldVersion = ((Number) oldPackage.get("version")).longValue();
            if (newVersion != oldVersion) {
                throw new IllegalArgumentException("This package changed. Reload before saving.");
            }
        }

        String action = Objects.toString(body.get("action"), "save");
        if (action.equals("delete")) {
            if (packageId == 0) {
                throw new Missing();
            }
            execute(
                connection,
                "UPDATE travel_packages SET active=FALSE,deleted=TRUE,version=version+1 WHERE id=?",
                packageId
            );
            audit(connection, userId, "Deleted package from catalog", packageId);
            return packageId;
        }

        if (!action.equals("save")) {
            throw new IllegalArgumentException("Unknown package action.");
        }

        // Step 1: Resolve destination (existing ID or auto-create from name)
        long destinationId;
        String destinationName = "";
        if (body.containsKey("destination_name") && body.get("destination_name") != null) {
            destinationName = body.get("destination_name").toString().trim();
        }

        if (!destinationName.isEmpty()) {
            String dName = text(body, "destination_name", 120).trim();
            List<Map<String, Object>> found = query(
                connection,
                "SELECT id,active FROM destinations WHERE LOWER(TRIM(name))=LOWER(TRIM(?))",
                dName
            );
            if (!found.isEmpty()) {
                destinationId = ((Number) found.get(0).get("id")).longValue();
                if (!Boolean.TRUE.equals(found.get(0).get("active"))) {
                    execute(connection, "UPDATE destinations SET active=TRUE WHERE id=?", destinationId);
                }
            } else {
                destinationId = insert(
                    connection,
                    "INSERT INTO destinations(name,region,description,season,category,daily_cost,duration,activities,attractions,image,active) VALUES (?,?,?,?,?,?,?,?,?,?,TRUE)",
                    dName,
                    dName + " (Custom)",
                    "Explore " + dName + ", curated by our team.",
                    "All seasons",
                    "Adventure",
                    new BigDecimal("3500.00"),
                    number(body, "duration", 1, 60),
                    "Sightseeing, Local Culture, Exploration",
                    dName + " Highlights",
                    "city.jpg"
                );
            }
        } else {
            destinationId = number(body, "destination_id", 1, Long.MAX_VALUE);
        }

        // Step 2: Resolve hotel (existing ID or auto-create from name)
        long hotelId;
        String hotelName = "";
        if (body.containsKey("hotel_name") && body.get("hotel_name") != null) {
            hotelName = body.get("hotel_name").toString().trim();
        }

        if (!hotelName.isEmpty()) {
            String hName = text(body, "hotel_name", 120).trim();
            List<Map<String, Object>> found = query(
                connection,
                "SELECT id,active FROM hotels WHERE LOWER(TRIM(name))=LOWER(TRIM(?))",
                hName
            );
            if (!found.isEmpty()) {
                hotelId = ((Number) found.get(0).get("id")).longValue();
                if (!Boolean.TRUE.equals(found.get(0).get("active"))) {
                    execute(connection, "UPDATE hotels SET active=TRUE WHERE id=?", hotelId);
                }
            } else {
                String location = destinationName.isEmpty() ? "Central Location" : (destinationName + " Central");
                hotelId = insert(
                    connection,
                    "INSERT INTO hotels(name,location,description,amenities,active) VALUES (?,?,?,?,TRUE)",
                    hName,
                    location,
                    "Comfortable stay at " + hName + " with quality service and modern amenities.",
                    "Wi-Fi, Breakfast, Room Service, Air Conditioning"
                );
            }
        } else {
            hotelId = number(body, "hotel_id", 1, Long.MAX_VALUE);
        }

        // Step 3: Resolve transport option (existing ID or auto-create from name)
        long transportId;
        String transportName = "";
        if (body.containsKey("transport_name") && body.get("transport_name") != null) {
            transportName = body.get("transport_name").toString().trim();
        }

        if (!transportName.isEmpty()) {
            String tName = text(body, "transport_name", 120).trim();
            List<Map<String, Object>> found = query(
                connection,
                "SELECT id,active FROM transport_options WHERE LOWER(TRIM(name))=LOWER(TRIM(?))",
                tName
            );
            if (!found.isEmpty()) {
                transportId = ((Number) found.get(0).get("id")).longValue();
                if (!Boolean.TRUE.equals(found.get(0).get("active"))) {
                    execute(connection, "UPDATE transport_options SET active=TRUE WHERE id=?", transportId);
                }
            } else {
                String lower = tName.toLowerCase(Locale.ROOT);
                String mode = "Mixed";
                if (lower.contains("flight")) {
                    mode = "Flight";
                } else if (lower.contains("train")) {
                    mode = "Train";
                } else if (lower.contains("bus")) {
                    mode = "Bus";
                } else if (lower.contains("car") || lower.contains("cab") || lower.contains("taxi")) {
                    mode = "Car";
                } else if (lower.contains("ferry")) {
                    mode = "Ferry";
                }

                transportId = insert(
                    connection,
                    "INSERT INTO transport_options(name,mode,route_details,description,active) VALUES (?,?,?,?,TRUE)",
                    tName,
                    mode,
                    "Round trip transfers included",
                    "Convenient transportation via " + tName + "."
                );
            }
        } else {
            transportId = number(body, "transport_id", 1, Long.MAX_VALUE);
        }

        // Step 4: Validate linked entities if publishing package
        boolean active = Boolean.TRUE.equals(body.get("active"));
        Map<String, Object> linkedDest = row(connection, "destinations", destinationId);
        Map<String, Object> linkedHotel = row(connection, "hotels", hotelId);
        Map<String, Object> linkedTransport = row(connection, "transport_options", transportId);

        if (active) {
            boolean destActive = Boolean.TRUE.equals(linkedDest.get("active"));
            boolean hotelActive = Boolean.TRUE.equals(linkedHotel.get("active"));
            boolean transportActive = Boolean.TRUE.equals(linkedTransport.get("active"));
            if (!destActive || !hotelActive || !transportActive) {
                throw new IllegalArgumentException(
                    "Published packages require an active destination, hotel and transport option."
                );
            }
        }

        BigDecimal price = money(body, "price");
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("Package price must be greater than zero.");
        }

        String image = "mountains.jpg";
        if (body.containsKey("image") && body.get("image") != null && !body.get("image").toString().trim().isEmpty()) {
            String candidate = body.get("image").toString().trim();
            if (candidate.matches("api/package-photo\\?id=\\d+") ||
                candidate.equals("mountains.jpg") ||
                candidate.equals("coast.jpg") ||
                candidate.equals("city.jpg")) {
                image = candidate;
            }
        } else if (oldPackage != null && oldPackage.get("image") != null) {
            image = oldPackage.get("image").toString();
        }

        byte[] photoBytes = null;
        if (body.containsKey("photo") && body.get("photo") != null && !body.get("photo").toString().trim().isEmpty()) {
            photoBytes = ApiController.photo(body);
        }

        String packageName = text(body, "name", 150);
        String description = text(body, "description", 4000);
        String inclusions = text(body, "inclusions", 4000);
        String exclusions = optional(body, "exclusions", 2000);
        String itinerary = text(body, "itinerary", 4000);
        String terms = text(body, "terms", 2000);
        long duration = number(body, "duration", 1, 60);
        long maxPeople = number(body, "max_people", 1, 50);

        // Step 5: Save package row
        if (packageId == 0) {
            packageId = insert(
                connection,
                "INSERT INTO travel_packages(destination_id,hotel_id,transport_id,name,description,inclusions,exclusions,itinerary,terms,price,duration,max_people,image,active) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                destinationId,
                hotelId,
                transportId,
                packageName,
                description,
                inclusions,
                exclusions,
                itinerary,
                terms,
                price,
                duration,
                maxPeople,
                image,
                active
            );
        } else {
            execute(
                connection,
                "UPDATE travel_packages SET destination_id=?,hotel_id=?,transport_id=?,name=?,description=?,inclusions=?,exclusions=?,itinerary=?,terms=?,price=?,duration=?,max_people=?,image=?,active=?,version=version+1 WHERE id=?",
                destinationId,
                hotelId,
                transportId,
                packageName,
                description,
                inclusions,
                exclusions,
                itinerary,
                terms,
                price,
                duration,
                maxPeople,
                image,
                active,
                packageId
            );
        }

        // Step 6: Store custom uploaded photo if provided
        if (photoBytes != null) {
            execute(connection, "DELETE FROM package_photos WHERE package_id=?", packageId);
            execute(
                connection,
                "INSERT INTO package_photos(package_id,photo) VALUES (?,?)",
                packageId,
                photoBytes
            );
            execute(
                connection,
                "UPDATE travel_packages SET image=? WHERE id=?",
                "api/package-photo?id=" + packageId,
                packageId
            );
        }

        audit(connection, userId, "Saved package", packageId);
        return packageId;
    }

    // =========================================================================
    // 4. TRAVELER BOOKING WORKFLOW (Client)
    // =========================================================================

    /**
     * Submits a package booking request for a traveler.
     * Features:
     * - Idempotency: Uses request_key to prevent duplicate charges or double bookings.
     * - Snapshot Storage: Serializes full package, hotel, and transport state at time of booking
     *   so future catalog updates don't alter the traveler's agreement.
     */
    public static long book(Connection connection, long userId, Map<String, Object> body)
        throws Exception {
        String key = text(body, "request_key", 80);
        if (!key.matches("[A-Za-z0-9-]{16,80}")) {
            throw new IllegalArgumentException("Invalid booking request key.");
        }

        // Idempotency check: if already processed, return existing booking ID
        List<Map<String, Object>> existing = query(
            connection,
            "SELECT id FROM package_bookings WHERE user_id=? AND request_key=?",
            userId,
            key
        );
        if (!existing.isEmpty()) {
            return ((Number) existing.get(0).get("id")).longValue();
        }

        long packageId = number(body, "package_id", 1, Long.MAX_VALUE);
        Map<String, Object> pkg = row(connection, "travel_packages", packageId);
        if (!Boolean.TRUE.equals(pkg.get("active")) || Boolean.TRUE.equals(pkg.get("deleted"))) {
            throw new IllegalArgumentException("This package is no longer available.");
        }

        // Version verification to ensure traveler agreed to the latest price and terms
        long reqVersion = number(body, "version", 1, Integer.MAX_VALUE);
        long curVersion = ((Number) pkg.get("version")).longValue();
        if (reqVersion != curVersion) {
            throw new IllegalArgumentException(
                "The package changed. Reload and review its current price and inclusions."
            );
        }

        long destId = ((Number) pkg.get("destination_id")).longValue();
        long hotelId = ((Number) pkg.get("hotel_id")).longValue();
        long transId = ((Number) pkg.get("transport_id")).longValue();

        Map<String, Object> destRow = row(connection, "destinations", destId);
        Map<String, Object> hotelRow = row(connection, "hotels", hotelId);
        Map<String, Object> transRow = row(connection, "transport_options", transId);

        if (!Boolean.TRUE.equals(destRow.get("active")) ||
            !Boolean.TRUE.equals(hotelRow.get("active")) ||
            !Boolean.TRUE.equals(transRow.get("active"))) {
            throw new IllegalArgumentException("This package is temporarily unavailable.");
        }

        long maxAllowedPeople = ((Number) pkg.get("max_people")).longValue();
        long people = number(body, "people", 1, maxAllowedPeople);
        LocalDate startDate = date(body, "start_date");
        LocalDate today = LocalDate.now();

        if (startDate.isBefore(today) || startDate.isAfter(today.plusYears(2))) {
            throw new IllegalArgumentException("Choose a departure date within the next two years.");
        }

        if (!Boolean.TRUE.equals(body.get("accepted_terms"))) {
            throw new IllegalArgumentException("Review and accept the package terms first.");
        }

        // Capture immutable snapshot of the package catalog entry
        List<Map<String, Object>> snapshotList = query(connection, CATALOG_SQL + " WHERE p.id=?", packageId);
        Map<String, Object> snapshot = snapshotList.get(0);

        BigDecimal unitPrice = new BigDecimal(pkg.get("price").toString());
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(people));

        List<Map<String, Object>> countResult = query(
            connection,
            "SELECT COUNT(*) AS n FROM package_bookings WHERE user_id=?",
            userId
        );
        long userBookingCount = ((Number) countResult.get(0).get("n")).longValue();
        if (userBookingCount >= 200) {
            throw new IllegalArgumentException("Your account has reached its booking limit.");
        }

        long durationDays = ((Number) pkg.get("duration")).longValue();
        LocalDate endDate = startDate.plusDays(durationDays - 1);
        String snapshotJson = JSON.writeValueAsString(snapshot);

        return insert(
            connection,
            "INSERT INTO package_bookings(user_id,package_id,package_name,start_date,end_date,people,total,snapshot,request_key) VALUES (?,?,?,?,?,?,?,?,?)",
            userId,
            packageId,
            pkg.get("name"),
            startDate,
            endDate,
            people,
            total,
            snapshotJson,
            key
        );
    }

    // =========================================================================
    // 5. BOOKING STATUS & SIMULATED PAYMENT LEDGER
    // =========================================================================

    /**
     * Updates booking workflow status:
     * - REQUESTED -> CONFIRMED -> COMPLETED or CANCELLED
     */
    public static void status(
        Connection connection,
        long userId,
        Map<String, Object> body,
        boolean admin
    ) throws SQLException {
        long id = number(body, "id", 1, Long.MAX_VALUE);
        Map<String, Object> booking = row(connection, "package_bookings", id);

        // Authorization check for client vs admin
        if (!admin) {
            long bookingUserId = ((Number) booking.get("user_id")).longValue();
            if (bookingUserId != userId) {
                throw new Missing();
            }
        }

        String current = booking.get("status").toString();
        String next;
        if (admin) {
            next = choice(body, "status", "CONFIRMED", "CANCELLED", "COMPLETED");
        } else {
            next = "CANCELLED";
        }

        if (next.equals(current)) {
            return;
        }

        // Validate state transitions
        boolean valid = false;
        if (current.equals("REQUESTED") && (next.equals("CONFIRMED") || next.equals("CANCELLED"))) {
            valid = true;
        } else if (current.equals("CONFIRMED") && (next.equals("CANCELLED") || next.equals("COMPLETED"))) {
            valid = true;
        }

        if (!valid) {
            throw new IllegalArgumentException("That booking status transition is not allowed.");
        }

        if (!admin && !current.equals("REQUESTED")) {
            throw new IllegalArgumentException("Contact support to cancel a confirmed booking.");
        }

        if (next.equals("COMPLETED")) {
            LocalDate bookingEndDate = LocalDate.parse(booking.get("end_date").toString());
            if (bookingEndDate.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("A booking cannot be completed before its end date.");
            }
        }

        execute(connection, "UPDATE package_bookings SET status=? WHERE id=?", next, id);
        audit(connection, userId, "Booking " + next, id);
    }

    /**
     * Allows travelers to simulate paying their booking balance in full.
     */
    public static long demoPayment(Connection connection, long userId, Map<String, Object> body)
        throws SQLException {
        long bookingId = number(body, "booking_id", 1, Long.MAX_VALUE);
        Map<String, Object> booking = row(connection, "package_bookings", bookingId);

        long bookingUserId = ((Number) booking.get("user_id")).longValue();
        if (bookingUserId != userId) {
            throw new Missing();
        }

        String key = text(body, "request_key", 80);
        if (!key.matches("[A-Za-z0-9-]{16,80}")) {
            throw new IllegalArgumentException("Invalid request key.");
        }

        String reference = "DEMO-" + bookingId + "-" + key;
        List<Map<String, Object>> previous = query(
            connection,
            "SELECT id FROM booking_payments WHERE reference=? AND booking_id=?",
            reference,
            bookingId
        );
        if (!previous.isEmpty()) {
            return ((Number) previous.get(0).get("id")).longValue();
        }

        // Calculate remaining balance
        BigDecimal paid = BigDecimal.ZERO;
        List<Map<String, Object>> paymentRows = query(
            connection,
            "SELECT kind,amount FROM booking_payments WHERE booking_id=?",
            bookingId
        );
        for (Map<String, Object> p : paymentRows) {
            BigDecimal a = new BigDecimal(p.get("amount").toString());
            if ("RECEIPT".equals(p.get("kind"))) {
                paid = paid.add(a);
            } else {
                paid = paid.subtract(a);
            }
        }

        BigDecimal total = new BigDecimal(booking.get("total").toString());
        BigDecimal due = total.subtract(paid);

        Map<String, Object> paymentData = new HashMap<>();
        paymentData.put("booking_id", bookingId);
        paymentData.put("kind", "RECEIPT");
        paymentData.put("amount", due);
        paymentData.put("reference", reference);
        paymentData.put("note", "SIMULATED payment. No money collected.");

        return payment(connection, userId, paymentData);
    }

    /**
     * Records a payment entry (RECEIPT or REFUND) in the booking payments ledger.
     * Enforces financial accounting rules:
     * - Receipts cannot exceed the booking's unpaid balance.
     * - Receipts cannot be added to cancelled bookings.
     * - Refunds cannot exceed what has been paid.
     */
    public static long payment(Connection connection, long userId, Map<String, Object> body)
        throws SQLException {
        long bookingId = number(body, "booking_id", 1, Long.MAX_VALUE);
        Map<String, Object> booking = row(connection, "package_bookings", bookingId);

        String kind = choice(body, "kind", "RECEIPT", "REFUND");
        BigDecimal amount = money(body, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero.");
        }

        String reference = text(body, "reference", 150);
        String note = text(body, "note", 1000);

        // Check for duplicate payment reference
        List<Map<String, Object>> duplicate = query(
            connection,
            "SELECT * FROM booking_payments WHERE reference=?",
            reference
        );

        if (!duplicate.isEmpty()) {
            Map<String, Object> existingPayment = duplicate.get(0);
            long dupBookingId = ((Number) existingPayment.get("booking_id")).longValue();
            String dupKind = existingPayment.get("kind").toString();
            BigDecimal dupAmount = new BigDecimal(existingPayment.get("amount").toString());
            String dupNote = existingPayment.get("note").toString();

            if (dupBookingId == bookingId &&
                dupKind.equals(kind) &&
                dupAmount.compareTo(amount) == 0 &&
                dupNote.equals(note)) {
                return ((Number) existingPayment.get("id")).longValue();
            }
            throw new IllegalArgumentException("This reference is already used for another payment entry.");
        }

        // Calculate current paid amount
        BigDecimal paid = BigDecimal.ZERO;
        List<Map<String, Object>> paymentRows = query(
            connection,
            "SELECT kind,amount FROM booking_payments WHERE booking_id=?",
            bookingId
        );
        for (Map<String, Object> p : paymentRows) {
            BigDecimal a = new BigDecimal(p.get("amount").toString());
            if ("RECEIPT".equals(p.get("kind"))) {
                paid = paid.add(a);
            } else {
                paid = paid.subtract(a);
            }
        }

        BigDecimal total = new BigDecimal(booking.get("total").toString());

        // Validate receipt constraints
        if (kind.equals("RECEIPT")) {
            boolean isCancelled = booking.get("status").equals("CANCELLED");
            boolean exceedsBalance = paid.add(amount).compareTo(total) > 0;
            if (isCancelled || exceedsBalance) {
                throw new IllegalArgumentException(
                    "Cannot record receipts for cancelled bookings or more than the outstanding balance."
                );
            }
        }

        // Validate refund constraints
        if (kind.equals("REFUND") && amount.compareTo(paid) > 0) {
            throw new IllegalArgumentException("Refund cannot exceed the amount currently paid.");
        }

        long paymentId = insert(
            connection,
            "INSERT INTO booking_payments(booking_id,actor_id,kind,amount,reference,note) VALUES (?,?,?,?,?,?)",
            bookingId,
            userId,
            kind,
            amount,
            reference,
            note
        );

        audit(connection, userId, "Recorded " + kind + " for booking #" + bookingId, paymentId);
        return paymentId;
    }

    private PackageService() {
        // Utility service class - prevent direct instantiation
    }
}
