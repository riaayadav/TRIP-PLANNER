package com.tripplanner;

import com.zaxxer.hikari.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

// Database manages connection pooling and helper methods for SQL queries and execution
public final class Database implements AutoCloseable {

    private final HikariDataSource pool;

    // Helper to get configuration from system property or environment variable
    public static String env(String key, String fallback) {
        String prop = System.getProperty(key);
        if (prop != null) {
            return prop;
        }
        String envVal = System.getenv(key);
        if (envVal != null) {
            return envVal;
        }
        return fallback;
    }

    public Database() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(
            env("DB_URL", "jdbc:mysql://localhost:3306/trip_planner?connectionTimeZone=UTC")
        );
        cfg.setUsername(env("DB_USER", "trip_planner"));
        cfg.setPassword(env("DB_PASSWORD", ""));

        int poolSize = Integer.parseInt(env("DB_POOL_SIZE", "5"));
        if (poolSize < 1 || poolSize > 20) {
            throw new IllegalArgumentException("DB_POOL_SIZE must be between 1 and 20");
        }
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(10000);
        pool = new HikariDataSource(cfg);
    }

    // Obtains a database connection from the connection pool
    public Connection connection() throws SQLException {
        return pool.getConnection();
    }

    // Executes a SELECT query and returns the results as a list of column-to-value maps
    public static List<Map<String, Object>> query(Connection connection, String sql, Object... args)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            statement.setMaxRows(1000);
            statement.setQueryTimeout(10);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Map<String, Object>> resultList = new ArrayList<>();
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columnCount = metadata.getColumnCount();

                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        Object columnValue = resultSet.getObject(i);
                        if (columnValue instanceof java.sql.Date || columnValue instanceof Timestamp) {
                            columnValue = columnValue.toString();
                        }
                        String columnName = metadata.getColumnLabel(i).toLowerCase(Locale.ROOT);
                        row.put(columnName, columnValue);
                    }
                    resultList.add(row);
                }
                return resultList;
            }
        }
    }

    // Executes an UPDATE or DELETE statement and returns the affected rows count
    public static int execute(Connection connection, String sql, Object... args)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            statement.setQueryTimeout(10);
            return statement.executeUpdate();
        }
    }

    // Executes an INSERT statement and returns the generated primary key
    public static long insert(Connection connection, String sql, Object... args)
        throws SQLException {
        try (
            PreparedStatement statement = connection.prepareStatement(
                sql,
                Statement.RETURN_GENERATED_KEYS
            )
        ) {
            bind(statement, args);
            statement.executeUpdate();
            try (ResultSet resultSet = statement.getGeneratedKeys()) {
                if (!resultSet.next()) {
                    throw new SQLException("No generated key");
                }
                return resultSet.getLong(1);
            }
        }
    }

    // Sets parameter values on a PreparedStatement
    private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

    // Initializes database tables and seed data if needed
    public void initialize() throws Exception {
        try (Connection connection = connection()) {
            if ("false".equals(env("DB_INITIALIZE", "true"))) {
                List<Map<String, Object>> schemaRows = query(
                    connection,
                    "SELECT version FROM schema_version WHERE version=4"
                );
                if (schemaRows.isEmpty()) {
                    throw new SQLException(
                        "Schema version 4 is required; run the controlled migration before deployment"
                    );
                }
                return;
            }

            execute(
                connection,
                "CREATE TABLE IF NOT EXISTS schema_version (version INT PRIMARY KEY)"
            );

            // Step 1: Base schema
            List<Map<String, Object>> v1Check = query(
                connection,
                "SELECT version FROM schema_version WHERE version=1"
            );
            if (v1Check.isEmpty()) {
                String schema = new String(
                    Objects.requireNonNull(
                        getClass().getResourceAsStream("/schema.sql")
                    ).readAllBytes(),
                    StandardCharsets.UTF_8
                );
                String[] parts = schema.split(";");
                for (int i = 0; i < parts.length; i++) {
                    String part = parts[i];
                    if (part == null || part.trim().isEmpty()) {
                        continue;
                    }
                    if (part.trim().startsWith("CREATE INDEX")) {
                        boolean exists = false;
                        try (
                            ResultSet indexes = connection
                                .getMetaData()
                                .getIndexInfo(
                                    connection.getCatalog(),
                                    null,
                                    "trips",
                                    false,
                                    false
                                )
                        ) {
                            while (indexes.next()) {
                                if ("idx_trips_user".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                                    exists = true;
                                    break;
                                }
                            }
                        }
                        if (exists) {
                            continue;
                        }
                    }
                    execute(connection, part);
                }
                execute(connection, "INSERT INTO schema_version(version) VALUES (1)");
            }

            // Step 2: Web sessions
            List<Map<String, Object>> v2Check = query(
                connection,
                "SELECT version FROM schema_version WHERE version=2"
            );
            if (v2Check.isEmpty()) {
                execute(
                    connection,
                    "CREATE TABLE IF NOT EXISTS web_sessions (session_key VARCHAR(64) PRIMARY KEY,uid BIGINT,auth_version INT,csrf VARCHAR(64) NOT NULL,expires_at BIGINT NOT NULL)"
                );
                boolean indexed = false;
                try (
                    ResultSet indexes = connection
                        .getMetaData()
                        .getIndexInfo(connection.getCatalog(), null, "web_sessions", false, false)
                ) {
                    while (indexes.next()) {
                        if ("idx_web_sessions_expiry".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                            indexed = true;
                            break;
                        }
                    }
                }
                if (!indexed) {
                    execute(
                        connection,
                        "CREATE INDEX idx_web_sessions_expiry ON web_sessions(expires_at)"
                    );
                }
                execute(connection, "INSERT INTO schema_version(version) VALUES (2)");
            }

            // Step 3: Packages schema
            List<Map<String, Object>> v3Check = query(
                connection,
                "SELECT version FROM schema_version WHERE version=3"
            );
            if (v3Check.isEmpty()) {
                String pkgSchema = new String(
                    Objects.requireNonNull(
                        getClass().getResourceAsStream("/packages.sql")
                    ).readAllBytes(),
                    StandardCharsets.UTF_8
                );
                String[] pkgParts = pkgSchema.split(";");
                for (int i = 0; i < pkgParts.length; i++) {
                    String part = pkgParts[i];
                    if (part != null && !part.trim().isEmpty()) {
                        execute(connection, part);
                    }
                }
                execute(connection, "INSERT INTO schema_version(version) VALUES (3)");
            }

            execute(
                connection,
                "CREATE TABLE IF NOT EXISTS package_photos (package_id BIGINT PRIMARY KEY, photo MEDIUMBLOB NOT NULL, FOREIGN KEY(package_id) REFERENCES travel_packages(id) ON DELETE CASCADE)"
            );

            // Step 4: Destination photos
            List<Map<String, Object>> v4Check = query(
                connection,
                "SELECT version FROM schema_version WHERE version=4"
            );
            if (v4Check.isEmpty()) {
                execute(
                    connection,
                    "CREATE TABLE IF NOT EXISTS destination_photos (destination_id BIGINT PRIMARY KEY, photo MEDIUMBLOB NOT NULL, FOREIGN KEY(destination_id) REFERENCES destinations(id) ON DELETE CASCADE)"
                );
                execute(connection, "INSERT INTO schema_version(version) VALUES (4)");
            }

            // Seed default destinations if table is empty
            List<Map<String, Object>> destCheck = query(connection, "SELECT id FROM destinations LIMIT 1");
            if (destCheck.isEmpty()) {
                seed(connection);
            }

            // Seed default settings if empty
            List<Map<String, Object>> settingsCheck = query(connection, "SELECT setting_key FROM settings LIMIT 1");
            if (settingsCheck.isEmpty()) {
                execute(
                    connection,
                    "INSERT INTO settings VALUES (?,?)",
                    "announcement",
                    "Your next chapter starts somewhere new."
                );
                execute(
                    connection,
                    "INSERT INTO settings VALUES (?,?)",
                    "registration_enabled",
                    "true"
                );
                execute(
                    connection,
                    "INSERT INTO settings VALUES (?,?)",
                    "support_email",
                    "support@example.com"
                );
            }

            // Create admin user if configured and none exists
            String adminEmail = env("ADMIN_EMAIL", "");
            String adminPassword = env("ADMIN_PASSWORD", "");
            if (!adminEmail.trim().isEmpty()) {
                List<Map<String, Object>> adminCheck = query(
                    connection,
                    "SELECT id FROM users WHERE role='ADMIN' LIMIT 1"
                );
                if (adminCheck.isEmpty()) {
                    Security.validatePassword(adminPassword);
                    execute(
                        connection,
                        "INSERT INTO users(name,email,password,role) VALUES (?,?,?,'ADMIN')",
                        "Administrator",
                        adminEmail.toLowerCase(Locale.ROOT),
                        Security.hash(adminPassword)
                    );
                }
            }
        }
    }

    // Seeds initial sample destinations
    private void seed(Connection connection) throws SQLException {
        String[][] rows = {
            {
                "Manali",
                "Himachal Pradesh, India",
                "Pine forests, mountain trails and slow mornings in the Himalayas.",
                "Summer",
                "Adventure",
                "2800",
                "5",
                "Hiking,Photography,Nature",
                "Solang Valley,Old Manali,Jogini Falls",
                "mountains.jpg",
            },
            {
                "Goa",
                "Western India",
                "A coastal escape with beach walks, heritage streets and local food.",
                "Winter",
                "Beach",
                "3500",
                "4",
                "Beach,Food,Culture",
                "Fontainhas,Palolem Beach,Fort Aguada",
                "coast.jpg",
            },
            {
                "Munnar",
                "Kerala, India",
                "Rolling tea gardens, misty viewpoints and peaceful hill-country stays.",
                "Monsoon",
                "Nature",
                "2600",
                "3",
                "Nature,Photography,Relaxation",
                "Tea Museum,Mattupetty Dam,Top Station",
                "mountains.jpg",
            },
            {
                "Jaipur",
                "Rajasthan, India",
                "Explore pink sandstone architecture, royal courtyards and colorful markets.",
                "Winter",
                "Culture",
                "3000",
                "3",
                "Culture,Food,Shopping",
                "Amber Fort,City Palace,Hawa Mahal",
                "city.jpg",
            },
            {
                "Udaipur",
                "Rajasthan, India",
                "Lakeside sunsets, palaces and unhurried walks through the old city.",
                "Autumn",
                "Relaxation",
                "3200",
                "3",
                "Culture,Relaxation,Photography",
                "Lake Pichola,City Palace,Saheliyon Ki Bari",
                "city.jpg",
            },
            {
                "Rishikesh",
                "Uttarakhand, India",
                "Riverside walks, yoga and outdoor adventures in the Himalayan foothills.",
                "Autumn",
                "Adventure",
                "2200",
                "3",
                "Adventure,Nature,Wellness",
                "Triveni Ghat,Beatles Ashram,Neer Garh Falls",
                "mountains.jpg",
            },
        };

        for (int i = 0; i < rows.length; i++) {
            String[] rowData = rows[i];
            execute(
                connection,
                "INSERT INTO destinations(name,region,description,season,category,daily_cost,duration,activities,attractions,image) VALUES (?,?,?,?,?,?,?,?,?,?)",
                (Object[]) rowData
            );
        }
    }

    // Closes the connection pool
    @Override
    public void close() {
        pool.close();
    }
}
