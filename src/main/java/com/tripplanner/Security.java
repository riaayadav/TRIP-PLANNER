package com.tripplanner;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

// Security handles password hashing, verification and random token generation
public final class Security {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final int PASSWORD_ITERATIONS = 600000;
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private Security() {}

    // Generates a URL-safe random token
    public static String token() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Validates password length
    public static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Use a password of 12 to 128 characters.");
        }
    }

    // Hashes a password using PBKDF2 with salt
    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        String encodedSalt = Base64.getEncoder().encodeToString(salt);
        byte[] derivedHash = derive(password, salt, PASSWORD_ITERATIONS);
        String encodedHash = Base64.getEncoder().encodeToString(derivedHash);
        // Format: iterations:salt:hash
        return PASSWORD_ITERATIONS + ":" + encodedSalt + ":" + encodedHash;
    }

    // Verifies an entered password against the stored hash
    public static boolean verify(String password, String encoded) {
        try {
            String[] parts = encoded.split(":");
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[2]);
            byte[] actualHash = derive(password, salt, iterations);
            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    // Helper method to derive key bytes using PBKDF2WithHmacSHA256
    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(keySpec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        } finally {
            keySpec.clearPassword();
        }
    }
}
