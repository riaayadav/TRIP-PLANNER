package com.tripplanner;

// SchemaInitializer is a utility class to run database migrations from the command line
public final class SchemaInitializer {

    public static void main(String[] args) throws Exception {
        String dbInit = Database.env("DB_INITIALIZE", "true");
        if (!"true".equals(dbInit)) {
            throw new IllegalStateException("Set DB_INITIALIZE=true for the migration command");
        }

        try (Database database = new Database()) {
            database.initialize();
        }

        System.out.println("Database initialized and migrated to schema version 4.");
    }

    private SchemaInitializer() {}
}
