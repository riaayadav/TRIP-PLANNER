package com.tripplanner;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Security {
    private static final SecureRandom RANDOM=new SecureRandom();
    public static String token(){byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public static void validatePassword(String p){if(p==null||p.length()<12||p.length()>128)throw new IllegalArgumentException("Use a password of 12 to 128 characters.");}
    public static String hash(String p){byte[] salt=new byte[16];RANDOM.nextBytes(salt);return "600000:"+Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(derive(p,salt,600000));}
    private static byte[] derive(String p,byte[] salt,int iterations){PBEKeySpec spec=new PBEKeySpec(p.toCharArray(),salt,iterations,256);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}catch(Exception e){throw new IllegalStateException(e);}finally{spec.clearPassword();}}
    public static boolean verify(String p,String encoded){try{String[] a=encoded.split(":");return MessageDigest.isEqual(Base64.getDecoder().decode(a[2]),derive(p,Base64.getDecoder().decode(a[1]),Integer.parseInt(a[0])));}catch(RuntimeException e){return false;}}
    private Security(){}
}
