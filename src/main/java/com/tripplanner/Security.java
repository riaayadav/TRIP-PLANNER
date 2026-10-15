package com.tripplanner;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Security handles password hashing, verification, and cryptographic tokens.
 * 
 * Key Security Concepts Used:
 * 1. PBKDF2-HMAC-SHA256: Industry standard key-derivation function that makes password
 *    cracking computationally expensive for attackers.
 * 2. Cryptographic Salt: 16 random bytes added to each password before hashing to defeat
 *    precomputed Rainbow Table attacks.
 * 3. 600,000 Iterations: High work factor recommended by OWASP to protect user credentials.
 * 4. Constant-Time Comparison: MessageDigest.isEqual prevents side-channel timing attacks.
 * 5. Secure Random Tokens: Used for CSRF protection and database session keys.
 */
public final class Security {

    // Cryptographically secure random number generator
    private static final SecureRandom RANDOM = new SecureRandom();

    // Configuration constants
    private static final int TOKEN_BYTES = 32;            // 256-bit random tokens
    private static final int SALT_BYTES = 16;             // 128-bit unique salt per password
    private static final int HASH_BITS = 256;             // 256-bit output hash
    private static final int PASSWORD_ITERATIONS = 600000; // Work factor (iterations)
    private static final int MIN_PASSWORD_LENGTH = 12;    // Minimum allowed password length
    private static final int MAX_PASSWORD_LENGTH = 128;   // Maximum allowed password length

    private Security() {
        // Utility class - prevent instantiation
    }

    /**
     * Generates a URL-safe cryptographically secure random token.
     * Used for CSRF tokens and session identifiers.
     */
    public static String token() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Validates that a password satisfies the 12 to 128 character policy.
     */
    public static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Use a password of 12 to 128 characters.");
        }
    }

    /**
     * Hashes a plaintext password using PBKDF2 with a fresh random salt.
     * Returns formatted string: "iterations:base64Salt:base64Hash"
     */
    public static String hash(String password) {
        // 1. Generate a unique 16-byte random salt
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        String encodedSalt = Base64.getEncoder().encodeToString(salt);

        // 2. Derive the 256-bit hash using PBKDF2
        byte[] derivedHash = derive(password, salt, PASSWORD_ITERATIONS);
        String encodedHash = Base64.getEncoder().encodeToString(derivedHash);

        // 3. Store iterations, salt, and hash together separated by colons
        return PASSWORD_ITERATIONS + ":" + encodedSalt + ":" + encodedHash;
    }

    /**
     * Verifies an entered password against a stored hash string.
     * Uses constant-time equality check to prevent timing attacks.
     */
    public static boolean verify(String password, String encoded) {
        try {
            // Parse: iterations:salt:hash
            String[] parts = encoded.split(":");
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[2]);

            // Hash the candidate password with the same salt and iteration count
            byte[] actualHash = derive(password, salt, iterations);

            // Constant-time comparison: returns true if identical, false otherwise
            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Internal helper method to compute PBKDF2WithHmacSHA256 key derivation.
     */
    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(keySpec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to derive password hash", exception);
        } finally {
            // Overwrite plaintext password characters in memory for security
            keySpec.clearPassword();
        }
    }
}
