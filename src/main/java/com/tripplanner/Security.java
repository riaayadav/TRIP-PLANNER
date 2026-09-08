package com.tripplanner;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Security {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final int PASSWORD_ITERATIONS = 600_000;
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private Security() {}

    public static String token() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static void validatePassword(String password) {
        if (
            password == null ||
            password.length() < MIN_PASSWORD_LENGTH ||
            password.length() > MAX_PASSWORD_LENGTH
        ) {
            throw new IllegalArgumentException("Use a password of 12 to 128 characters.");
        }
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        String encodedSalt = Base64.getEncoder().encodeToString(salt);
        String encodedHash = Base64.getEncoder().encodeToString(
            derive(password, salt, PASSWORD_ITERATIONS)
        );
        // Keep the iteration count with each hash so existing passwords remain verifiable.
        return PASSWORD_ITERATIONS + ":" + encodedSalt + ":" + encodedHash;
    }

    public static boolean verify(String password, String encoded) {
        try {
            String[] parts = encoded.split(":");
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            int iterations = Integer.parseInt(parts[0]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(keySpec)
                .getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        } finally {
            keySpec.clearPassword();
        }
    }
}
