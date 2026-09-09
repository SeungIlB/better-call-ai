package kr.co.legalai.auth.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Component
public class IdentityCrypto {
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final String encryptionKeyId;
    private final String encryptionKeyBase64;
    private final String lookupKeyBase64;

    public IdentityCrypto(
            @Value("${security.identity.encryption-key-id}") String encryptionKeyId,
            @Value("${security.identity.encryption-key-base64}") String encryptionKeyBase64,
            @Value("${security.identity.lookup-key-base64}") String lookupKeyBase64
    ) {
        this.encryptionKeyId = encryptionKeyId;
        this.encryptionKeyBase64 = encryptionKeyBase64;
        this.lookupKeyBase64 = lookupKeyBase64;
    }

    public String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public String lookupHash(String normalizedEmail) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(decodeKey(lookupKeyBase64, "이메일 조회 키"), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalizedEmail.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("이메일 조회 키를 사용할 수 없습니다.", exception);
        }
    }

    public byte[] encryptEmail(String normalizedEmail) {
        try {
            byte[] nonce = new byte[GCM_NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(aesKey(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce)
            );
            byte[] ciphertext = cipher.doFinal(normalizedEmail.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("이메일을 암호화할 수 없습니다.", exception);
        }
    }

    public String decryptEmail(byte[] encryptedEmail, String keyId) {
        if (!encryptionKeyId.equals(keyId) || encryptedEmail.length <= GCM_NONCE_BYTES) {
            throw new IllegalStateException("이메일 암호화 키를 확인할 수 없습니다.");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(encryptedEmail);
            byte[] nonce = new byte[GCM_NONCE_BYTES];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKey(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce)
            );
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("이메일을 복호화할 수 없습니다.", exception);
        }
    }

    public String encryptionKeyId() {
        return encryptionKeyId;
    }

    private byte[] aesKey() {
        byte[] key = decodeKey(encryptionKeyBase64, "이메일 암호화 키");
        if (key.length != 32) {
            throw new IllegalStateException("이메일 암호화 키는 32바이트여야 합니다.");
        }
        return key;
    }

    private byte[] decodeKey(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + "가 설정되지 않았습니다.");
        }
        try {
            byte[] key = Base64.getDecoder().decode(value);
            if (key.length < 32) {
                throw new IllegalStateException(name + "는 32바이트 이상이어야 합니다.");
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + "는 Base64 형식이어야 합니다.", exception);
        }
    }
}
