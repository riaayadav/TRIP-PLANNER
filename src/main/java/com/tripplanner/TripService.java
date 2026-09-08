package com.tripplanner;

import static com.tripplanner.Database.*;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Business rules shared by the Servlet controller and integration tests. */
public final class TripService {

    public static String text(Map<String, Object> body, String key, int max) {
        Object v = body.get(key);
        String s = v == null ? "" : v.toString().trim();
        if (s.isEmpty() || s.length() > max) throw new IllegalArgumentException(
            key + " is required (maximum " + max + " characters)."
        );
        return s;
    }

    public static String optional(Map<String, Object> body, String key, int max) {
        String s = Objects.toString(body.get(key), "").trim();
        if (s.length() > max) throw new IllegalArgumentException(key + " is too long.");
        return s;
    }

    public static String password(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (
            !(v instanceof String s) || s.isEmpty() || s.length() > 128
        ) throw new IllegalArgumentException("Invalid " + key + ".");
        return s;
    }

    public static String listText(Map<String, Object> body, String key, int max) {
        String value = text(body, key, max);
        String result = String.join(
            ", ",
            Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList()
        );
        if (result.isEmpty()) throw new IllegalArgumentException(
            "Enter at least one " + key + " entry."
        );
        if (result.length() > max) throw new IllegalArgumentException(key + " is too long.");
        return result;
    }

    public static long number(Map<String, Object> body, String key, long min, long max) {
        try {
            long n = Long.parseLong(Objects.toString(body.get(key), ""));
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + key + ".");
        }
    }

    public static BigDecimal money(Map<String, Object> body, String key) {
        try {
            BigDecimal n = new BigDecimal(Objects.toString(body.get(key), "0"));
            if (
                n.signum() < 0 || n.compareTo(new BigDecimal("99999999.99")) > 0 || n.scale() > 2
            ) throw new NumberFormatException();
            return n.setScale(2);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + key + " amount.");
        }
    }

    public static String choice(Map<String, Object> body, String key, String... choices) {
        String s = text(body, key, 40);
        if (!Arrays.asList(choices).contains(s)) throw new IllegalArgumentException(
            "Invalid " + key + "."
        );
        return s;
    }

    public static LocalDate date(Map<String, Object> body, String key) {
        try {
            return LocalDate.parse(text(body, key, 10));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + key + " date.");
        }
    }

    public static Map<String, Object> owned(Connection connection, long user, long trip)
        throws SQLException {
        List<Map<String, Object>> rows = query(
            connection,
            "SELECT * FROM trips WHERE id=? AND user_id=? FOR UPDATE",
            trip,
            user
        );
        if (rows.isEmpty()) throw new Missing();
        return rows.get(0);
    }

    public static void editable(Map<String, Object> t) {
        if (!"PLANNED".equals(t.get("status"))) throw new IllegalArgumentException(
            "Only planned trips can be edited."
        );
    }

    public static void completed(Map<String, Object> t) {
        if (!"COMPLETED".equals(t.get("status"))) throw new IllegalArgumentException(
            "Complete this trip before adding memories or feedback."
        );
    }

    public static long saveTrip(Connection connection, long user, Map<String, Object> body)
        throws SQLException {
        long id = body.containsKey("id") ? number(body, "id", 1, Long.MAX_VALUE) : 0;
        LocalDate start = date(body, "start_date"),
            end = date(body, "end_date");
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days < 1 || days > 60) throw new IllegalArgumentException(
            "Trips must last between 1 and 60 days."
        );
        long destination = number(body, "destination_id", 1, Long.MAX_VALUE),
            people = number(body, "people", 1, 50);
        Map<String, Object> existing = id > 0 ? owned(connection, user, id) : null;
        List<Map<String, Object>> ds = query(
            connection,
            "SELECT * FROM destinations WHERE id=?",
            destination
        );
        if (
            ds.isEmpty() ||
            (!Boolean.TRUE.equals(ds.get(0).get("active")) &&
                (existing == null ||
                    ((Number) existing.get("destination_id")).longValue() != destination))
        ) throw new IllegalArgumentException("Choose an available destination.");
        String title = text(body, "title", 150),
            interests = optional(body, "interests", 500);
        BigDecimal budget = money(body, "budget");
        if (id > 0) {
            Map<String, Object> trip = existing;
            editable(trip);
            if (
                !query(
                    connection,
                    "SELECT id FROM itinerary WHERE trip_id=? AND day_number>?",
                    id,
                    days
                ).isEmpty()
            ) throw new IllegalArgumentException(
                "Remove itinerary days outside the new duration first."
            );
            execute(
                connection,
                "UPDATE trips SET destination_id=?,title=?,start_date=?,end_date=?,people=?,interests=?,budget=? WHERE id=?",
                destination,
                title,
                start,
                end,
                people,
                interests,
                budget,
                id
            );
        } else {
            if (start.isBefore(LocalDate.now())) throw new IllegalArgumentException(
                "New trips must start today or later."
            );
            if (
                (
                    (Number) query(
                        connection,
                        "SELECT COUNT(*) AS n FROM trips WHERE user_id=?",
                        user
                    )
                        .get(0)
                        .get("n")
                ).longValue() >= 200
            ) throw new IllegalArgumentException("Your account has reached the 200-trip limit.");
            id = insert(
                connection,
                "INSERT INTO trips(user_id,destination_id,title,start_date,end_date,people,interests,budget) VALUES (?,?,?,?,?,?,?,?)",
                user,
                destination,
                title,
                start,
                end,
                people,
                interests,
                budget
            );
            String itineraryMode = body.containsKey("itinerary_mode")
                ? choice(body, "itinerary_mode", "suggested", "blank")
                : "suggested";
            if (itineraryMode.equals("suggested")) {
                String[] sights = Arrays.stream(ds.get(0).get("attractions").toString().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
                if (sights.length == 0) sights = new String[] { ds.get(0).get("name").toString() };
                for (int day = 1; day <= days; day++) execute(
                    connection,
                    "INSERT INTO itinerary(trip_id,day_number,time_slot,title,note) VALUES (?,?,?,?,?)",
                    id,
                    day,
                    "09:00",
                    sights[(day - 1) % sights.length].trim(),
                    "Suggested stop. Check opening hours and local conditions before travel."
                );
            }
            String[][] packing = {
                { "Clothes", "Comfortable clothes" },
                { "Documents", "ID and travel reservations" },
                { "Toiletries", "Toothbrush and personal care" },
                { "Electronics", "Phone charger and power bank" },
                {
                    "Season essentials",
                    switch (ds.get(0).get("season").toString()) {
                        case "Winter" -> "Warm layers";
                        case "Monsoon" -> "Rain jacket and waterproof bag";
                        default -> "Sunscreen and reusable water bottle";
                    },
                },
            };
            for (String[] p : packing)
                execute(
                    connection,
                    "INSERT INTO packing(trip_id,category,label) VALUES (?,?,?)",
                    id,
                    p[0],
                    p[1]
                );
        }
        return id;
    }

    public static void child(
        Connection connection,
        long user,
        String entity,
        Map<String, Object> body
    ) throws SQLException {
        long trip = number(body, "trip_id", 1, Long.MAX_VALUE);
        Map<String, Object> t = owned(connection, user, trip);
        editable(t);
        long id = body.containsKey("id") ? number(body, "id", 1, Long.MAX_VALUE) : 0;
        String action = Objects.toString(body.get("action"), "save");
        // Entity is an internal allowlist, never a request-supplied SQL identifier.
        if (
            !Set.of("packing", "itinerary", "expenses").contains(entity)
        ) throw new IllegalArgumentException("Invalid item type.");
        if (
            !Set.of("save", "delete", "toggle").contains(action) ||
            ("toggle".equals(action) && !entity.equals("packing"))
        ) throw new IllegalArgumentException("Invalid item action.");
        if (
            id > 0 &&
            query(
                connection,
                "SELECT id FROM " + entity + " WHERE id=? AND trip_id=?",
                id,
                trip
            ).isEmpty()
        ) throw new Missing();
        if (action.equals("delete")) {
            if (id == 0) throw new Missing();
            execute(connection, "DELETE FROM " + entity + " WHERE id=? AND trip_id=?", id, trip);
            return;
        }
        if (
            id == 0 &&
            (
                (Number) query(
                    connection,
                    "SELECT COUNT(*) AS n FROM " + entity + " WHERE trip_id=?",
                    trip
                )
                    .get(0)
                    .get("n")
            ).longValue() >= 500
        ) throw new IllegalArgumentException("Each trip supports up to 500 items per list.");
        if (entity.equals("packing")) {
            if (action.equals("toggle")) {
                if (id == 0) throw new Missing();
                execute(
                    connection,
                    "UPDATE packing SET packed=? WHERE id=? AND trip_id=?",
                    Boolean.TRUE.equals(body.get("packed")),
                    id,
                    trip
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
                ),
                label = text(body, "label", 120);
            if (id > 0) execute(
                connection,
                "UPDATE packing SET category=?,label=? WHERE id=? AND trip_id=?",
                category,
                label,
                id,
                trip
            );
            else execute(
                connection,
                "INSERT INTO packing(trip_id,category,label) VALUES (?,?,?)",
                trip,
                category,
                label
            );
        } else if (entity.equals("expenses")) {
            String category = choice(
                    body,
                    "category",
                    "Transport",
                    "Hotel",
                    "Food",
                    "Activities",
                    "Shopping",
                    "Other"
                ),
                label = text(body, "label", 120);
            BigDecimal amount = money(body, "amount");
            if (id > 0) execute(
                connection,
                "UPDATE expenses SET category=?,label=?,amount=? WHERE id=? AND trip_id=?",
                category,
                label,
                amount,
                id,
                trip
            );
            else execute(
                connection,
                "INSERT INTO expenses(trip_id,category,label,amount) VALUES (?,?,?,?)",
                trip,
                category,
                label,
                amount
            );
        } else {
            long days =
                ChronoUnit.DAYS.between(
                    LocalDate.parse(t.get("start_date").toString()),
                    LocalDate.parse(t.get("end_date").toString())
                ) + 1;
            long day = number(body, "day_number", 1, days);
            String time = text(body, "time_slot", 5);
            if (!time.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) throw new IllegalArgumentException(
                "Use a valid 24-hour time."
            );
            String title = text(body, "title", 150),
                note = optional(body, "note", 1000);
            if (id > 0) execute(
                connection,
                "UPDATE itinerary SET day_number=?,time_slot=?,title=?,note=? WHERE id=? AND trip_id=?",
                day,
                time,
                title,
                note,
                id,
                trip
            );
            else execute(
                connection,
                "INSERT INTO itinerary(trip_id,day_number,time_slot,title,note) VALUES (?,?,?,?,?)",
                trip,
                day,
                time,
                title,
                note
            );
        }
    }

    public static final class Missing extends RuntimeException {}

    private TripService() {}
}
