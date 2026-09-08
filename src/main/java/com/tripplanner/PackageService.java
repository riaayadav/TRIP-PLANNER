package com.tripplanner;

import static com.tripplanner.Database.*;
import static com.tripplanner.TripService.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/** Package catalog and booking ledger. Amounts and permissions are enforced server-side. */
public final class PackageService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CATALOG =
        "SELECT p.*,d.name AS destination,d.season,h.name AS hotel,h.location AS hotel_location,h.description AS hotel_description,h.amenities,t.name AS transport,t.mode,t.route_details,t.description AS transport_description FROM travel_packages p JOIN destinations d ON d.id=p.destination_id JOIN hotels h ON h.id=p.hotel_id JOIN transport_options t ON t.id=p.transport_id";

    public static List<Map<String, Object>> catalog(Connection connection, boolean admin)
        throws SQLException {
        return query(
            connection,
            CATALOG +
                " WHERE p.deleted=FALSE" +
                (admin
                    ? ""
                    : " AND p.active=TRUE AND d.active=TRUE AND h.active=TRUE AND t.active=TRUE") +
                " ORDER BY p.id DESC"
        );
    }

    public static Map<String, Object> overview(Connection connection, long userId, boolean admin)
        throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("packages", catalog(connection, admin));
        List<Map<String, Object>> bookings = query(
            connection,
            "SELECT b.*,u.name AS customer,u.email FROM package_bookings b JOIN users u ON u.id=b.user_id" +
                (admin ? "" : " WHERE b.user_id=?") +
                " ORDER BY b.id DESC",
            admin ? new Object[] {} : new Object[] { userId }
        );
        for (var b : bookings) {
            var payments = query(
                connection,
                "SELECT id,kind,amount,reference,note,created_at FROM booking_payments WHERE booking_id=? ORDER BY id",
                b.get("id")
            );
            BigDecimal paid = BigDecimal.ZERO,
                received = BigDecimal.ZERO;
            for (var p : payments) {
                BigDecimal a = new BigDecimal(p.get("amount").toString());
                if ("RECEIPT".equals(p.get("kind"))) {
                    paid = paid.add(a);
                    received = received.add(a);
                } else paid = paid.subtract(a);
            }
            BigDecimal total = new BigDecimal(b.get("total").toString());
            b.put("paid", paid);
            b.put("balance", total.subtract(paid));
            b.put("payments", payments);
            b.put(
                "payment_status",
                paid.signum() == 0
                    ? received.signum() > 0
                        ? "REFUNDED"
                        : "UNPAID"
                    : paid.compareTo(total) == 0
                      ? "PAID"
                      : "PARTIAL"
            );
        }
        out.put("bookings", bookings);
        if (admin) {
            out.put("hotels", query(connection, "SELECT * FROM hotels ORDER BY name"));
            out.put(
                "transport",
                query(connection, "SELECT * FROM transport_options ORDER BY name")
            );
            out.put(
                "destinations",
                query(connection, "SELECT id,name,active FROM destinations ORDER BY name")
            );
        }
        return out;
    }

    private static Map<String, Object> row(Connection connection, String table, long id)
        throws SQLException {
        return query(connection, "SELECT * FROM " + table + " WHERE id=? FOR UPDATE", id)
            .stream()
            .findFirst()
            .orElseThrow(Missing::new);
    }

    private static void audit(Connection connection, long userId, String action, long id)
        throws SQLException {
        execute(
            connection,
            "INSERT INTO audit_log(actor_id,action,target_id) VALUES (?,?,?)",
            userId,
            action,
            id
        );
    }

    public static long supplier(
        Connection connection,
        long userId,
        Map<String, Object> b,
        boolean hotel
    ) throws SQLException {
        String table = hotel ? "hotels" : "transport_options",
            fk = hotel ? "hotel_id" : "transport_id";
        long id = b.containsKey("id") ? number(b, "id", 1, Long.MAX_VALUE) : 0;
        String action = Objects.toString(b.get("action"), "save");
        if (!Set.of("save", "delete").contains(action)) throw new IllegalArgumentException(
            "Unknown action."
        );
        if (id > 0) row(connection, table, id);
        else if (action.equals("delete")) throw new Missing();
        boolean active = Boolean.TRUE.equals(b.get("active")) && !action.equals("delete");
        if (
            id > 0 &&
            !active &&
            !query(
                connection,
                "SELECT id FROM travel_packages WHERE " +
                    fk +
                    "=? AND active=TRUE AND deleted=FALSE",
                id
            ).isEmpty()
        ) throw new IllegalArgumentException(
            "Unpublish linked packages before removing this supplier."
        );
        if (action.equals("delete")) {
            execute(connection, "UPDATE " + table + " SET active=FALSE WHERE id=?", id);
            audit(connection, userId, "Archived " + table, id);
            return id;
        }
        List<Object> a = new ArrayList<>();
        a.add(text(b, "name", 120));
        if (hotel) {
            a.add(text(b, "location", 200));
            a.add(text(b, "description", 2000));
            a.add(optional(b, "amenities", 2000));
        } else {
            a.add(choice(b, "mode", "Flight", "Train", "Bus", "Car", "Ferry", "Mixed"));
            a.add(text(b, "route_details", 500));
            a.add(text(b, "description", 2000));
        }
        a.add(active);
        String fields = hotel
            ? "name,location,description,amenities,active"
            : "name,mode,route_details,description,active";
        if (id == 0) id = insert(
            connection,
            "INSERT INTO " + table + "(" + fields + ") VALUES (?,?,?,?,?)",
            a.toArray()
        );
        else {
            a.add(id);
            execute(
                connection,
                "UPDATE " + table + " SET " + fields.replace(",", "=?,") + "=? WHERE id=?",
                a.toArray()
            );
        }
        audit(connection, userId, "Saved " + table, id);
        return id;
    }

    public static long save(Connection connection, long userId, Map<String, Object> b)
        throws Exception {
        long id = b.containsKey("id") ? number(b, "id", 1, Long.MAX_VALUE) : 0;
        Map<String, Object> old = null;
        if (id > 0) {
            old = row(connection, "travel_packages", id);
            if (Boolean.TRUE.equals(old.get("deleted"))) throw new Missing();
            if (
                number(b, "version", 1, Integer.MAX_VALUE) !=
                ((Number) old.get("version")).longValue()
            ) throw new IllegalArgumentException("This package changed. Reload before saving.");
        }
        String action = Objects.toString(b.get("action"), "save");
        if (action.equals("delete")) {
            if (id == 0) throw new Missing();
            execute(
                connection,
                "UPDATE travel_packages SET active=FALSE,deleted=TRUE,version=version+1 WHERE id=?",
                id
            );
            audit(connection, userId, "Deleted package from catalog", id);
            return id;
        }
        if (!action.equals("save")) throw new IllegalArgumentException("Unknown package action.");

        long destination;
        if (
            b.containsKey("destination_name") &&
            !Objects.toString(b.get("destination_name"), "").trim().isBlank()
        ) {
            String dName = text(b, "destination_name", 120).trim();
            var found = query(
                connection,
                "SELECT id,active FROM destinations WHERE LOWER(TRIM(name))=LOWER(TRIM(?))",
                dName
            );
            if (!found.isEmpty()) {
                destination = ((Number) found.get(0).get("id")).longValue();
                if (!Boolean.TRUE.equals(found.get(0).get("active"))) execute(
                    connection,
                    "UPDATE destinations SET active=TRUE WHERE id=?",
                    destination
                );
            } else {
                destination = insert(
                    connection,
                    "INSERT INTO destinations(name,region,description,season,category,daily_cost,duration,activities,attractions,image,active) VALUES (?,?,?,?,?,?,?,?,?,?,TRUE)",
                    dName,
                    dName + " (Custom)",
                    "Explore " + dName + ", curated by our team.",
                    "All seasons",
                    "Adventure",
                    new BigDecimal("3500.00"),
                    number(b, "duration", 1, 60),
                    "Sightseeing, Local Culture, Exploration",
                    dName + " Highlights",
                    "city.jpg"
                );
            }
        } else {
            destination = number(b, "destination_id", 1, Long.MAX_VALUE);
        }

        long hotel;
        if (
            b.containsKey("hotel_name") &&
            !Objects.toString(b.get("hotel_name"), "").trim().isBlank()
        ) {
            String hName = text(b, "hotel_name", 120).trim();
            var found = query(
                connection,
                "SELECT id,active FROM hotels WHERE LOWER(TRIM(name))=LOWER(TRIM(?))",
                hName
            );
            if (!found.isEmpty()) {
                hotel = ((Number) found.get(0).get("id")).longValue();
                if (!Boolean.TRUE.equals(found.get(0).get("active"))) execute(
                    connection,
                    "UPDATE hotels SET active=TRUE WHERE id=?",
                    hotel
                );
            } else {
                String loc =
                    b.containsKey("destination_name") &&
                    !Objects.toString(b.get("destination_name"), "").isBlank()
                        ? text(b, "destination_name", 120).trim() + " Central"
                        : "Central Location";
                hotel = insert(
                    connection,
                    "INSERT INTO hotels(name,location,description,amenities,active) VALUES (?,?,?,?,TRUE)",
                    hName,
                    loc,
                    "Comfortable stay at " + hName + " with quality service and modern amenities.",
                    "Wi-Fi, Breakfast, Room Service, Air Conditioning"
                );
            }
        } else {
            hotel = number(b, "hotel_id", 1, Long.MAX_VALUE);
        }

        long transport;
        if (
            b.containsKey("transport_name") &&
            !Objects.toString(b.get("transport_name"), "").trim().isBlank()
        ) {
            String tName = text(b, "transport_name", 120).trim();
            var found = query(
                connection,
                "SELECT id,active FROM transport_options WHERE LOWER(TRIM(name))=LOWER(TRIM(?))",
                tName
            );
            if (!found.isEmpty()) {
                transport = ((Number) found.get(0).get("id")).longValue();
                if (!Boolean.TRUE.equals(found.get(0).get("active"))) execute(
                    connection,
                    "UPDATE transport_options SET active=TRUE WHERE id=?",
                    transport
                );
            } else {
                String lower = tName.toLowerCase(Locale.ROOT);
                String mode = lower.contains("flight")
                    ? "Flight"
                    : lower.contains("train")
                      ? "Train"
                      : lower.contains("bus")
                        ? "Bus"
                        : lower.contains("car") || lower.contains("cab") || lower.contains("taxi")
                          ? "Car"
                          : lower.contains("ferry")
                            ? "Ferry"
                            : "Mixed";
                transport = insert(
                    connection,
                    "INSERT INTO transport_options(name,mode,route_details,description,active) VALUES (?,?,?,?,TRUE)",
                    tName,
                    mode,
                    "Round trip transfers included",
                    "Convenient transportation via " + tName + "."
                );
            }
        } else {
            transport = number(b, "transport_id", 1, Long.MAX_VALUE);
        }

        boolean active = Boolean.TRUE.equals(b.get("active"));
        for (var ref : List.of(
            Map.entry("destinations", destination),
            Map.entry("hotels", hotel),
            Map.entry("transport_options", transport)
        )) {
            var linked = row(connection, ref.getKey(), ref.getValue());
            if (
                active && !Boolean.TRUE.equals(linked.get("active"))
            ) throw new IllegalArgumentException(
                "Published packages require an active destination, hotel and transport option."
            );
        }
        BigDecimal price = money(b, "price");
        if (price.signum() <= 0) throw new IllegalArgumentException(
            "Package price must be greater than zero."
        );

        String image = "mountains.jpg";
        if (b.containsKey("image") && !Objects.toString(b.get("image"), "").trim().isBlank()) {
            String candidate = Objects.toString(b.get("image"), "").trim();
            if (
                candidate.matches("api/package-photo\\?id=\\d+") ||
                Set.of("mountains.jpg", "coast.jpg", "city.jpg").contains(candidate)
            ) {
                image = candidate;
            }
        } else if (old != null && old.get("image") != null) {
            image = old.get("image").toString();
        }

        byte[] photoBytes = null;
        if (b.containsKey("photo") && !Objects.toString(b.get("photo"), "").trim().isBlank()) {
            photoBytes = ApiController.photo(b);
        }

        Object[] values = {
            destination,
            hotel,
            transport,
            text(b, "name", 150),
            text(b, "description", 4000),
            text(b, "inclusions", 4000),
            optional(b, "exclusions", 2000),
            text(b, "itinerary", 4000),
            text(b, "terms", 2000),
            price,
            number(b, "duration", 1, 60),
            number(b, "max_people", 1, 50),
            image,
            active,
        };
        if (id == 0) id = insert(
            connection,
            "INSERT INTO travel_packages(destination_id,hotel_id,transport_id,name,description,inclusions,exclusions,itinerary,terms,price,duration,max_people,image,active) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            values
        );
        else {
            var a = new ArrayList<>(Arrays.asList(values));
            a.add(id);
            execute(
                connection,
                "UPDATE travel_packages SET destination_id=?,hotel_id=?,transport_id=?,name=?,description=?,inclusions=?,exclusions=?,itinerary=?,terms=?,price=?,duration=?,max_people=?,image=?,active=?,version=version+1 WHERE id=?",
                a.toArray()
            );
        }

        if (photoBytes != null) {
            execute(connection, "DELETE FROM package_photos WHERE package_id=?", id);
            execute(
                connection,
                "INSERT INTO package_photos(package_id,photo) VALUES (?,?)",
                id,
                photoBytes
            );
            execute(
                connection,
                "UPDATE travel_packages SET image=? WHERE id=?",
                "api/package-photo?id=" + id,
                id
            );
        }

        audit(connection, userId, "Saved package", id);
        return id;
    }

    public static long book(Connection connection, long userId, Map<String, Object> b)
        throws Exception {
        String key = text(b, "request_key", 80);
        if (!key.matches("[A-Za-z0-9-]{16,80}")) throw new IllegalArgumentException(
            "Invalid booking request key."
        );
        var existing = query(
            connection,
            "SELECT id FROM package_bookings WHERE user_id=? AND request_key=?",
            userId,
            key
        );
        if (!existing.isEmpty()) return ((Number) existing.get(0).get("id")).longValue();
        long pid = number(b, "package_id", 1, Long.MAX_VALUE);
        var p = row(connection, "travel_packages", pid);
        if (
            !Boolean.TRUE.equals(p.get("active")) || Boolean.TRUE.equals(p.get("deleted"))
        ) throw new IllegalArgumentException("This package is no longer available.");
        if (
            number(b, "version", 1, Integer.MAX_VALUE) != ((Number) p.get("version")).longValue()
        ) throw new IllegalArgumentException(
            "The package changed. Reload and review its current price and inclusions."
        );
        for (var ref : List.of(
            Map.entry("destinations", "destination_id"),
            Map.entry("hotels", "hotel_id"),
            Map.entry("transport_options", "transport_id")
        ))
            if (
                !Boolean.TRUE.equals(
                    row(connection, ref.getKey(), ((Number) p.get(ref.getValue())).longValue()).get(
                        "active"
                    )
                )
            ) throw new IllegalArgumentException("This package is temporarily unavailable.");
        long people = number(b, "people", 1, ((Number) p.get("max_people")).longValue());
        LocalDate date = date(b, "start_date");
        if (
            date.isBefore(LocalDate.now()) || date.isAfter(LocalDate.now().plusYears(2))
        ) throw new IllegalArgumentException("Choose a departure date within the next two years.");
        if (!Boolean.TRUE.equals(b.get("accepted_terms"))) throw new IllegalArgumentException(
            "Review and accept the package terms first."
        );
        var snapshot = query(connection, CATALOG + " WHERE p.id=?", pid).get(0);
        BigDecimal total = new BigDecimal(p.get("price").toString()).multiply(
            BigDecimal.valueOf(people)
        );
        if (
            (
                (Number) query(
                    connection,
                    "SELECT COUNT(*) AS n FROM package_bookings WHERE user_id=?",
                    userId
                )
                    .get(0)
                    .get("n")
            ).longValue() >= 200
        ) throw new IllegalArgumentException("Your account has reached its booking limit.");
        return insert(
            connection,
            "INSERT INTO package_bookings(user_id,package_id,package_name,start_date,end_date,people,total,snapshot,request_key) VALUES (?,?,?,?,?,?,?,?,?)",
            userId,
            pid,
            p.get("name"),
            date,
            date.plusDays(((Number) p.get("duration")).longValue() - 1),
            people,
            total,
            JSON.writeValueAsString(snapshot),
            key
        );
    }

    public static void status(
        Connection connection,
        long userId,
        Map<String, Object> b,
        boolean admin
    ) throws SQLException {
        long id = number(b, "id", 1, Long.MAX_VALUE);
        var booking = row(connection, "package_bookings", id);
        if (!admin && ((Number) booking.get("user_id")).longValue() != userId) throw new Missing();
        String current = booking.get("status").toString(),
            next = admin ? choice(b, "status", "CONFIRMED", "CANCELLED", "COMPLETED") : "CANCELLED";
        if (next.equals(current)) return;
        boolean valid =
            (current.equals("REQUESTED") &&
                (next.equals("CONFIRMED") || next.equals("CANCELLED"))) ||
            (current.equals("CONFIRMED") && (next.equals("CANCELLED") || next.equals("COMPLETED")));
        if (!valid) throw new IllegalArgumentException(
            "That booking status transition is not allowed."
        );
        if (!admin && !current.equals("REQUESTED")) throw new IllegalArgumentException(
            "Contact support to cancel a confirmed booking."
        );
        if (
            next.equals("COMPLETED") &&
            LocalDate.parse(booking.get("end_date").toString()).isAfter(LocalDate.now())
        ) throw new IllegalArgumentException("A booking cannot be completed before its end date.");
        execute(connection, "UPDATE package_bookings SET status=? WHERE id=?", next, id);
        audit(connection, userId, "Booking " + next, id);
    }

    public static long demoPayment(Connection connection, long userId, Map<String, Object> b)
        throws SQLException {
        long id = number(b, "booking_id", 1, Long.MAX_VALUE);
        var booking = row(connection, "package_bookings", id);
        if (((Number) booking.get("user_id")).longValue() != userId) throw new Missing();
        String key = text(b, "request_key", 80);
        if (!key.matches("[A-Za-z0-9-]{16,80}")) throw new IllegalArgumentException(
            "Invalid request key."
        );
        String reference = "DEMO-" + id + "-" + key;
        var previous = query(
            connection,
            "SELECT id FROM booking_payments WHERE reference=? AND booking_id=?",
            reference,
            id
        );
        if (!previous.isEmpty()) return ((Number) previous.get(0).get("id")).longValue();
        BigDecimal paid = BigDecimal.ZERO;
        for (var p : query(
            connection,
            "SELECT kind,amount FROM booking_payments WHERE booking_id=?",
            id
        )) {
            BigDecimal a = new BigDecimal(p.get("amount").toString());
            paid = paid.add(p.get("kind").equals("RECEIPT") ? a : a.negate());
        }
        BigDecimal due = new BigDecimal(booking.get("total").toString()).subtract(paid);
        return payment(
            connection,
            userId,
            Map.of(
                "booking_id",
                id,
                "kind",
                "RECEIPT",
                "amount",
                due,
                "reference",
                reference,
                "note",
                "SIMULATED payment. No money collected."
            )
        );
    }

    public static long payment(Connection connection, long userId, Map<String, Object> b)
        throws SQLException {
        long id = number(b, "booking_id", 1, Long.MAX_VALUE);
        var booking = row(connection, "package_bookings", id);
        String kind = choice(b, "kind", "RECEIPT", "REFUND");
        BigDecimal amount = money(b, "amount");
        if (amount.signum() <= 0) throw new IllegalArgumentException(
            "Payment amount must be greater than zero."
        );
        String reference = text(b, "reference", 150),
            note = text(b, "note", 1000);
        var duplicate = query(
            connection,
            "SELECT * FROM booking_payments WHERE reference=?",
            reference
        );
        if (!duplicate.isEmpty()) {
            var p = duplicate.get(0);
            if (
                ((Number) p.get("booking_id")).longValue() == id &&
                p.get("kind").equals(kind) &&
                new BigDecimal(p.get("amount").toString()).compareTo(amount) == 0 &&
                p.get("note").equals(note)
            ) return ((Number) p.get("id")).longValue();
            throw new IllegalArgumentException(
                "This reference is already used for another payment entry."
            );
        }
        BigDecimal paid = BigDecimal.ZERO;
        for (var p : query(
            connection,
            "SELECT kind,amount FROM booking_payments WHERE booking_id=?",
            id
        )) {
            BigDecimal a = new BigDecimal(p.get("amount").toString());
            paid = paid.add(p.get("kind").equals("RECEIPT") ? a : a.negate());
        }
        BigDecimal total = new BigDecimal(booking.get("total").toString());
        if (
            kind.equals("RECEIPT") &&
            (booking.get("status").equals("CANCELLED") || paid.add(amount).compareTo(total) > 0)
        ) throw new IllegalArgumentException(
            "Cannot record receipts for cancelled bookings or more than the outstanding balance."
        );
        if (kind.equals("REFUND") && amount.compareTo(paid) > 0) throw new IllegalArgumentException(
            "Refund cannot exceed the amount currently paid."
        );
        long payment = insert(
            connection,
            "INSERT INTO booking_payments(booking_id,actor_id,kind,amount,reference,note) VALUES (?,?,?,?,?,?)",
            id,
            userId,
            kind,
            amount,
            reference,
            note
        );
        audit(connection, userId, "Recorded " + kind + " for booking #" + id, payment);
        return payment;
    }
}
