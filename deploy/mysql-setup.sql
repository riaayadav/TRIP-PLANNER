-- Run as a MySQL database administrator. Replace the password before execution.
CREATE DATABASE IF NOT EXISTS trip_planner CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'trip_planner'@'localhost' IDENTIFIED BY 'REPLACE_WITH_A_LONG_RANDOM_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, INDEX, REFERENCES ON trip_planner.* TO 'trip_planner'@'localhost';
-- Initial startup creates schema version 2. After bootstrap, optionally revoke
-- CREATE, INDEX and REFERENCES and set DB_INITIALIZE=false for normal runtime.
