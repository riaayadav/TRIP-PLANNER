package com.tripplanner;

import static com.tripplanner.Database.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TripService provides business logic and validation for trips, itineraries, packing, and expenses.
 * 
 * Key Responsibilities:
 * 1. Input Sanitization & Validation: Validates strings, numbers, dates, monetary amounts, and choices.
 * 2. Authorization & Ownership Checks: Ensures users only view and modify their own trips.
 * 3. Smart Trip Creation: Computes trip duration, generates suggested daily itineraries from destination
 *    attractions, and creates a season-appropriate packing checklist.
 * 4. Child Entity Management: CRUD operations for daily itinerary slots, packing items, and expense records.
 */
public final class TripService {

    // =========================================================================
    // 1. INPUT SANITIZATION & VALIDATION HELPERS
    // =========================================================================

    /**
     * Validates a required text field. Throws IllegalArgumentException if missing or too long.
     */
    public static String text(Map<String, Object> body, String key, int max) {
        Object value = body.get(key);
        String str = "";
        if (value != null) {
            str = value.toString().trim();
        }
        if (str.isEmpty() || str.length() > max) {
            throw new IllegalArgumentException(key + " is required (maximum " + max + " characters).");
        }
        return str;
    }

    /**
     * Validates an optional text field. Trims whitespace and checks maximum character length.
     */
    public static String optional(Map<String, Object> body, String key, int max) {
        Object value = body.get(key);
        String str = "";
        if (value != null) {
            str = value.toString().trim();
        }
        if (str.length() > max) {
            throw new IllegalArgumentException(key + " is too long.");
        }
        return str;
    }

    /**
     * Validates a password input string (1 to 128 characters).
     */
    public static String password(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Invalid " + key + ".");
        }
        String pass = (String) value;
        if (pass.isEmpty() || pass.length() > 128) {
            throw new IllegalArgumentException("Invalid " + key + ".");
        }
        return pass;
    }

    /**
     * Parses and normalizes a comma-separated list of items (e.g. activities or attractions).
     */
    public static String listText(Map<String, Object> body, String key, int max) {
        String value = text(body, key, max);
        String[] parts = value.split(",");
        List<String> items = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String item = parts[i].trim();
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one " + key + " entry.");
        }
        String result = String.join(", ", items);
        if (result.length() > max) {
            throw new IllegalArgumentException(key + " is too long.");
        }
        return result;
    }

    /**
     * Parses and validates a number within a specified minimum and maximum range.
     */
    public static long number(Map<String, Object> body, String key, long min, long max) {
        try {
            long num = Long.parseLong(Objects.toString(body.get(key), ""));
            if (num < min || num > max) {
                throw new NumberFormatException();
            }
            return num;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + key + ".");
        }
    }

    /**
     * Parses and validates a monetary amount (currency with up to 2 decimal places).
     */
    public static BigDecimal money(Map<String, Object> body, String key) {
        try {
            BigDecimal amount = new BigDecimal(Objects.toString(body.get(key), "0"));
            if (amount.signum() < 0 || amount.compareTo(new BigDecimal("99999999.99")) > 0 || amount.scale() > 2) {
                throw new NumberFormatException();
            }
            return amount.setScale(2);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + key + " amount.");
        }
    }

    /**
     * Checks that an input value matches one of the allowed choices (enums/categories).
     */
    public static String choice(Map<String, Object> body, String key, String... choices) {
        String selected = text(body, key, 40);
        boolean found = false;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i].equals(selected)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Invalid " + key + ".");
        }
        return selected;
    }

    /**
     * Parses an ISO date (yyyy-MM-dd) string into a Java LocalDate.
     */
    public static LocalDate date(Map<String, Object> body, String key) {
        try {
            return LocalDate.parse(text(body, key, 10));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + key + " date.");
        }
    }

    // =========================================================================
    // 2. AUTHORIZATION & STATUS GUARDS
    // =========================================================================

    /**
     * Verifies that a trip exists and belongs to the authenticated user.
     * Uses 'FOR UPDATE' row locking to prevent race conditions during updates.
     */
    public static Map<String, Object> owned(Connection connection, long userId, long tripId)
        throws SQLException {
        List<Map<String, Object>> rows = query(
            connection,
            "SELECT * FROM trips WHERE id=? AND user_id=? FOR UPDATE",
            tripId,
            userId
        );
        if (rows.isEmpty()) {
            throw new Missing();
        }
        return rows.get(0);
    }

    /**
     * Enforces that a trip is still in PLANNED status before allowing modifications.
     */
    public static void editable(Map<String, Object> trip) {
        if (!"PLANNED".equals(trip.get("status"))) {
            throw new IllegalArgumentException("Only planned trips can be edited.");
        }
    }

    /**
     * Verifies that a trip has been marked as COMPLETED before memories or feedback can be added.
     */
    public static void completed(Map<String, Object> trip) {
        if (!"COMPLETED".equals(trip.get("status"))) {
            throw new IllegalArgumentException("Complete this trip before adding memories or feedback.");
        }
    }

    // =========================================================================
    // 3. SMART TRIP CREATION & EDITING
    // =========================================================================

    /**
     * Creates a new trip or updates an existing trip.
     * When creating a new trip:
     * - Auto-generates suggested daily itinerary items using the destination's top sights.
     * - Auto-generates a smart packing list customized for the destination's season.
     */
    public static long saveTrip(Connection connection, long userId, Map<String, Object> body)
        throws SQLException {
        long tripId = 0;
        if (body.containsKey("id")) {
            tripId = number(body, "id", 1, Long.MAX_VALUE);
        }

        // Calculate trip duration in days
        LocalDate startDate = date(body, "start_date");
        LocalDate endDate = date(body, "end_date");
        long totalDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (totalDays < 1 || totalDays > 60) {
            throw new IllegalArgumentException("Trips must last between 1 and 60 days.");
        }

        long destinationId = number(body, "destination_id", 1, Long.MAX_VALUE);
        long peopleCount = number(body, "people", 1, 50);

        Map<String, Object> existingTrip = null;
        if (tripId > 0) {
            existingTrip = owned(connection, userId, tripId);
        }

        // Verify destination exists and is active
        List<Map<String, Object>> destinationList = query(
            connection,
            "SELECT * FROM destinations WHERE id=?",
            destinationId
        );

        if (destinationList.isEmpty()) {
            throw new IllegalArgumentException("Choose an available destination.");
        }

        boolean destinationActive = Boolean.TRUE.equals(destinationList.get(0).get("active"));
        if (!destinationActive) {
            boolean sameAsExisting = false;
            if (existingTrip != null) {
                long currentDestId = ((Number) existingTrip.get("destination_id")).longValue();
                if (currentDestId == destinationId) {
                    sameAsExisting = true;
                }
            }
            if (!sameAsExisting) {
                throw new IllegalArgumentException("Choose an available destination.");
            }
        }

        String title = text(body, "title", 150);
        String interests = optional(body, "interests", 500);
        BigDecimal budget = money(body, "budget");

        // -----------------------------------------------------------------
        // CASE A: Updating an existing trip
        // -----------------------------------------------------------------
        if (tripId > 0) {
            editable(existingTrip);

            // Prevent shortening trip duration if itinerary items exist on removed days
            List<Map<String, Object>> extraDays = query(
                connection,
                "SELECT id FROM itinerary WHERE trip_id=? AND day_number>?",
                tripId,
                totalDays
            );
            if (!extraDays.isEmpty()) {
                throw new IllegalArgumentException("Remove itinerary days outside the new duration first.");
            }

            execute(
                connection,
                "UPDATE trips SET destination_id=?,title=?,start_date=?,end_date=?,people=?,interests=?,budget=? WHERE id=?",
                destinationId,
                title,
                startDate,
                endDate,
                peopleCount,
                interests,
                budget,
                tripId
            );
        } else {
            // -----------------------------------------------------------------
            // CASE B: Creating a brand new trip
            // -----------------------------------------------------------------
            if (startDate.isBefore(LocalDate.now())) {
                throw new IllegalArgumentException("New trips must start today or later.");
            }

            // Enforce account limit of 200 trips
            List<Map<String, Object>> countResult = query(
                connection,
                "SELECT COUNT(*) AS n FROM trips WHERE user_id=?",
                userId
            );
            long totalTrips = ((Number) countResult.get(0).get("n")).longValue();
            if (totalTrips >= 200) {
                throw new IllegalArgumentException("Your account has reached the 200-trip limit.");
            }

            // Insert new trip row into database
            tripId = insert(
                connection,
                "INSERT INTO trips(user_id,destination_id,title,start_date,end_date,people,interests,budget) VALUES (?,?,?,?,?,?,?,?)",
                userId,
                destinationId,
                title,
                startDate,
                endDate,
                peopleCount,
                interests,
                budget
            );

            // Auto-generate suggested daily itinerary items
            String itineraryMode = "suggested";
            if (body.containsKey("itinerary_mode")) {
                itineraryMode = choice(body, "itinerary_mode", "suggested", "blank");
            }

            if (itineraryMode.equals("suggested")) {
                String attractionsText = destinationList.get(0).get("attractions").toString();
                String[] rawAttractions = attractionsText.split(",");
                List<String> attractionList = new ArrayList<>();
                for (int i = 0; i < rawAttractions.length; i++) {
                    String attraction = rawAttractions[i].trim();
                    if (!attraction.isEmpty()) {
                        attractionList.add(attraction);
                    }
                }
                String[] sights;
                if (attractionList.isEmpty()) {
                    sights = new String[] { destinationList.get(0).get("name").toString() };
                } else {
                    sights = attractionList.toArray(new String[0]);
                }

                // Distribute sights across trip days
                for (int day = 1; day <= totalDays; day++) {
                    String place = sights[(day - 1) % sights.length].trim();
                    execute(
                        connection,
                        "INSERT INTO itinerary(trip_id,day_number,time_slot,title,note) VALUES (?,?,?,?,?)",
                        tripId,
                        day,
                        "09:00",
                        place,
                        "Suggested stop. Check opening hours and local conditions before travel."
                    );
                }
            }

            // Auto-generate customized packing list based on destination's season
            String season = destinationList.get(0).get("season").toString();
            String seasonItem = "Sunscreen and reusable water bottle";
            if ("Winter".equals(season)) {
                seasonItem = "Warm layers";
            } else if ("Monsoon".equals(season)) {
                seasonItem = "Rain jacket and waterproof bag";
            }

            String[][] defaultPackingList = {
                { "Clothes", "Comfortable clothes" },
                { "Documents", "ID and travel reservations" },
                { "Toiletries", "Toothbrush and personal care" },
                { "Electronics", "Phone charger and power bank" },
                { "Season essentials", seasonItem }
            };

            for (int i = 0; i < defaultPackingList.length; i++) {
                String[] item = defaultPackingList[i];
                execute(
                    connection,
                    "INSERT INTO packing(trip_id,category,label) VALUES (?,?,?)",
                    tripId,
                    item[0],
                    item[1]
                );
            }
        }
        return tripId;
    }

    // =========================================================================
    // 4. CHILD ENTITY MANAGEMENT (Itinerary, Packing & Expenses)
    // =========================================================================

    /**
     * Handles adding, updating, toggling, or deleting sub-items for a trip:
     * - 'packing': Packing items with category, label, and packed checkbox.
     * - 'expenses': Budget expenses with category, label, and cost.
     * - 'itinerary': Schedule stops with day number, 24-hr time slot, title, and note.
     */
    public static void child(
        Connection connection,
        long userId,
        String entity,
        Map<String, Object> body
    ) throws SQLException {
        long tripId = number(body, "trip_id", 1, Long.MAX_VALUE);
        Map<String, Object> trip = owned(connection, userId, tripId);
        editable(trip);

        long itemId = 0;
        if (body.containsKey("id")) {
            itemId = number(body, "id", 1, Long.MAX_VALUE);
        }

        String action = Objects.toString(body.get("action"), "save");

        // Validate allowed entity types and actions
        if (!entity.equals("packing") && !entity.equals("itinerary") && !entity.equals("expenses")) {
            throw new IllegalArgumentException("Invalid item type.");
        }
        if (!action.equals("save") && !action.equals("delete") && !action.equals("toggle")) {
            throw new IllegalArgumentException("Invalid item action.");
        }
        if (action.equals("toggle") && !entity.equals("packing")) {
            throw new IllegalArgumentException("Invalid item action.");
        }

        // Verify existing item ownership
        if (itemId > 0) {
            List<Map<String, Object>> existingItem = query(
                connection,
                "SELECT id FROM " + entity + " WHERE id=? AND trip_id=?",
                itemId,
                tripId
            );
            if (existingItem.isEmpty()) {
                throw new Missing();
            }
        }

        // Action: DELETE item
        if (action.equals("delete")) {
            if (itemId == 0) {
                throw new Missing();
            }
            execute(connection, "DELETE FROM " + entity + " WHERE id=? AND trip_id=?", itemId, tripId);
            return;
        }

        // Enforce 500-item maximum per trip list
        if (itemId == 0) {
            List<Map<String, Object>> countResult = query(
                connection,
                "SELECT COUNT(*) AS n FROM " + entity + " WHERE trip_id=?",
                tripId
            );
            long totalItems = ((Number) countResult.get(0).get("n")).longValue();
            if (totalItems >= 500) {
                throw new IllegalArgumentException("Each trip supports up to 500 items per list.");
            }
        }

        // Sub-entity: PACKING CHECKLIST
        if (entity.equals("packing")) {
            if (action.equals("toggle")) {
                if (itemId == 0) {
                    throw new Missing();
                }
                boolean isPacked = Boolean.TRUE.equals(body.get("packed"));
                execute(
                    connection,
                    "UPDATE packing SET packed=? WHERE id=? AND trip_id=?",
                    isPacked,
                    itemId,
                    tripId
                );
                return;
            }

            String category = choice(
                body,
                "category",
                "Clothes",
                "Documents",
                "Toiletries",
                "Electronics",
                "Season essentials",
                "Other"
            );
            String label = text(body, "label", 120);
            if (itemId > 0) {
                execute(
                    connection,
                    "UPDATE packing SET category=?,label=? WHERE id=? AND trip_id=?",
                    category,
                    label,
                    itemId,
                    tripId
                );
            } else {
                execute(
                    connection,
                    "INSERT INTO packing(trip_id,category,label) VALUES (?,?,?)",
                    tripId,
                    category,
                    label
                );
            }
        }
        // Sub-entity: EXPENSES & BUDGET ESTIMATES
        else if (entity.equals("expenses")) {
            String category = choice(
                body,
                "category",
                "Transport",
                "Hotel",
                "Food",
                "Activities",
                "Shopping",
                "Other"
            );
            String label = text(body, "label", 120);
            BigDecimal amount = money(body, "amount");
            if (itemId > 0) {
                execute(
                    connection,
                    "UPDATE expenses SET category=?,label=?,amount=? WHERE id=? AND trip_id=?",
                    category,
                    label,
                    amount,
                    itemId,
                    tripId
                );
            } else {
                execute(
                    connection,
                    "INSERT INTO expenses(trip_id,category,label,amount) VALUES (?,?,?,?)",
                    tripId,
                    category,
                    label,
                    amount
                );
            }
        }
        // Sub-entity: DAILY ITINERARY SLOTS
        else {
            LocalDate tripStart = LocalDate.parse(trip.get("start_date").toString());
            LocalDate tripEnd = LocalDate.parse(trip.get("end_date").toString());
            long totalDays = ChronoUnit.DAYS.between(tripStart, tripEnd) + 1;
            long dayNumber = number(body, "day_number", 1, totalDays);
            String timeSlot = text(body, "time_slot", 5);
            if (!timeSlot.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) {
                throw new IllegalArgumentException("Use a valid 24-hour time.");
            }
            String title = text(body, "title", 150);
            String note = optional(body, "note", 1000);
            if (itemId > 0) {
                execute(
                    connection,
                    "UPDATE itinerary SET day_number=?,time_slot=?,title=?,note=? WHERE id=? AND trip_id=?",
                    dayNumber,
                    timeSlot,
                    title,
                    note,
                    itemId,
                    tripId
                );
            } else {
                execute(
                    connection,
                    "INSERT INTO itinerary(trip_id,day_number,time_slot,title,note) VALUES (?,?,?,?,?)",
                    tripId,
                    dayNumber,
                    timeSlot,
                    title,
                    note
                );
            }
        }
    }

    /**
     * Custom exception thrown when a requested resource (trip, destination, item) is not found.
     * Handled by ApiController to return HTTP 404.
     */
    public static final class Missing extends RuntimeException {}

    private TripService() {
        // Utility service class - prevent direct instantiation
    }
}
