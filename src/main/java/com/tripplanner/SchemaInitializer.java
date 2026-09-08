package com.tripplanner;

/** Run once before deployment with DB_INITIALIZE=true and operator credentials. */
public final class SchemaInitializer {

    public static void main(String[] args) throws Exception {
        if (!"true".equals(Database.env("DB_INITIALIZE", "true"))) throw new IllegalStateException(
            "Set DB_INITIALIZE=true for the migration command"
        );
        try (Database database = new Database()) {
            database.initialize();
        }
        System.out.println("Database initialized and migrated to schema version 3.");
    }

    private SchemaInitializer() {}
}
