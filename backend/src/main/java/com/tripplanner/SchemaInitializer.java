package com.tripplanner;

/**
 * SchemaInitializer is a command-line utility to run database migrations.
 * 
 * Why this is useful:
 * - Before deploying the application or when upgrading versions, this class can be
 *   executed directly to initialize tables and seed data without starting Tomcat.
 * - Requires DB_INITIALIZE=true to be set.
 */
public final class SchemaInitializer {

    public static void main(String[] args) throws Exception {
        // 1. Verify that database initialization is explicitly enabled
        String dbInit = Database.env("DB_INITIALIZE", "true");
        if (!"true".equals(dbInit)) {
            throw new IllegalStateException("Set DB_INITIALIZE=true for the migration command");
        }

        // 2. Connect to the database and execute schema version migrations (v1 to v4)
        System.out.println("Starting database migration...");
        try (Database database = new Database()) {
            database.initialize();
        }

        System.out.println("Success: Database initialized and migrated to schema version 4.");
    }

    private SchemaInitializer() {
        // Utility class - prevent instantiation
    }
}
