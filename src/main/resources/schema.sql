-- =============================================================================
-- ROAM TRIP PLANNER - CORE DATABASE SCHEMA (MySQL 8 / Relational Database)
-- =============================================================================

-- TABLE 1: USERS
-- Stores traveler accounts and system administrators.
-- Passwords are encrypted using PBKDF2-HMAC-SHA256 with 16-byte random salts.
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(10) NOT NULL DEFAULT 'CLIENT',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    auth_version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- TABLE 2: DESTINATIONS
-- Master catalog of tourist destinations with seasonal advice, activities, and costs.
CREATE TABLE IF NOT EXISTS destinations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    region VARCHAR(120) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    season VARCHAR(30) NOT NULL,
    category VARCHAR(40) NOT NULL,
    daily_cost DECIMAL(12, 2) NOT NULL,
    duration INT NOT NULL,
    activities VARCHAR(1000) NOT NULL,
    attractions VARCHAR(1000) NOT NULL,
    image VARCHAR(500) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

-- TABLE 3: TRIPS
-- User-created personal trip itineraries linking a traveler to a destination.
CREATE TABLE IF NOT EXISTS trips (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    destination_id BIGINT NOT NULL,
    title VARCHAR(150) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    people INT NOT NULL,
    interests VARCHAR(500) NOT NULL,
    budget DECIMAL(12, 2) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id),
    FOREIGN KEY (destination_id) REFERENCES destinations (id)
);

-- Index to optimize querying trips by user and start date
CREATE INDEX idx_trips_user ON trips (user_id, start_date);

-- TABLE 4: EXPENSES
-- Itemized travel expenses for a trip (stay, meals, transport, sightseeing).
CREATE TABLE IF NOT EXISTS expenses (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_id BIGINT NOT NULL,
    category VARCHAR(30) NOT NULL,
    label VARCHAR(120) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    FOREIGN KEY (trip_id) REFERENCES trips (id) ON DELETE CASCADE
);

-- TABLE 5: ITINERARY
-- Day-by-day and time-slot schedule for planned trip activities.
CREATE TABLE IF NOT EXISTS itinerary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_id BIGINT NOT NULL,
    day_number INT NOT NULL,
    time_slot VARCHAR(5) NOT NULL,
    title VARCHAR(150) NOT NULL,
    note VARCHAR(1000) NOT NULL,
    FOREIGN KEY (trip_id) REFERENCES trips (id) ON DELETE CASCADE
);

-- TABLE 6: PACKING
-- Smart packing checklist categorized into clothing, documents, medical, and essentials.
CREATE TABLE IF NOT EXISTS packing (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_id BIGINT NOT NULL,
    category VARCHAR(30) NOT NULL,
    label VARCHAR(120) NOT NULL,
    packed BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (trip_id) REFERENCES trips (id) ON DELETE CASCADE
);

-- TABLE 7: WISHLIST
-- Many-to-many relationship table tracking destinations saved by users.
CREATE TABLE IF NOT EXISTS wishlist (
    user_id BIGINT NOT NULL,
    destination_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, destination_id),
    FOREIGN KEY (user_id) REFERENCES users (id),
    FOREIGN KEY (destination_id) REFERENCES destinations (id)
);

-- TABLE 8: MEMORIES
-- Travel journal entries with notes, dates, and optional photo attachments.
CREATE TABLE IF NOT EXISTS memories (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trip_id BIGINT NOT NULL,
    title VARCHAR(150) NOT NULL,
    memory_date DATE NOT NULL,
    note VARCHAR(2000) NOT NULL,
    photo MEDIUMBLOB,
    FOREIGN KEY (user_id) REFERENCES users (id),
    FOREIGN KEY (trip_id) REFERENCES trips (id)
);

-- TABLE 9: FEEDBACK
-- Trip ratings and reviews submitted by travelers with admin response tracking.
CREATE TABLE IF NOT EXISTS feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    trip_id BIGINT NOT NULL UNIQUE,
    rating INT NOT NULL,
    message VARCHAR(2000) NOT NULL,
    response VARCHAR(2000) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users (id),
    FOREIGN KEY (trip_id) REFERENCES trips (id)
);

-- TABLE 10: SETTINGS
-- System key-value configuration table (e.g., site name, currency).
CREATE TABLE IF NOT EXISTS settings (
    setting_key VARCHAR(60) PRIMARY KEY,
    setting_value VARCHAR(2000) NOT NULL
);

-- TABLE 11: AUDIT LOG
-- Compliance and security log tracking administrative actions and record changes.
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor_id BIGINT NOT NULL,
    action VARCHAR(120) NOT NULL,
    target_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- TABLE 12: LOGIN ATTEMPTS
-- Sliding-window tracking table to prevent brute-force login attempts.
CREATE TABLE IF NOT EXISTS login_attempts (
    attempt_key VARCHAR(254) PRIMARY KEY,
    attempts INT NOT NULL,
    window_start BIGINT NOT NULL
);
