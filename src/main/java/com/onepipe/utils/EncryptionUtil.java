package com.onepipe.utils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

public class EncryptionUtil {
    public static String encryptTripleDES(String dataToEncrypt, String encryptionKey) {
        try {
            // 1. Hash the key with MD5
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digestOfPassword = md.digest(encryptionKey.getBytes("UTF-16LE"));

            // 2. Prepare the 24-byte key
            byte[] keyBytes = Arrays.copyOf(digestOfPassword, 24);
            for (int j = 0, k = 16; j < 8;) {
                keyBytes[k++] = keyBytes[j++];
            }

            // 3. Setup Cipher
            SecretKey secretKey = new SecretKeySpec(keyBytes, 0, 24, "DESede");
            IvParameterSpec iv = new IvParameterSpec(new byte[8]); // default zero IV
            Cipher cipher = Cipher.getInstance("DESede/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv);

            // 4. Encrypt
            byte[] plainTextBytes = dataToEncrypt.getBytes("UTF-16LE");
            byte[] cipherText = cipher.doFinal(plainTextBytes);

            // 5. Return Base64 String
            return Base64.getEncoder().encodeToString(cipherText);

        } catch (Exception e) {
            throw new RuntimeException("Error encrypting data: " + e.getMessage());
        }
    }

    // Helper for the Signature Header: MD5(request_ref;client_secret)
    public static String generateSignature(String requestRef, String clientSecret) {
        try {
            String raw = requestRef + ";" + clientSecret;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(raw.getBytes());

            // Convert byte array to Hex String
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error generating signature", e);
        }
    }
}
