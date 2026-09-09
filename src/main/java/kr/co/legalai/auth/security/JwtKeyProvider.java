package kr.co.legalai.auth.security;

import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Component
public class JwtKeyProvider {
    private final String keyId;
    private final String privateKeyBase64;
    private final String publicKeyBase64;
    private volatile RSAKey key;

    public JwtKeyProvider(
            @Value("${security.jwt.key-id}") String keyId,
            @Value("${security.jwt.private-key-base64}") String privateKeyBase64,
            @Value("${security.jwt.public-key-base64}") String publicKeyBase64
    ) {
        this.keyId = keyId;
        this.privateKeyBase64 = privateKeyBase64;
        this.publicKeyBase64 = publicKeyBase64;
    }

    public RSAKey getSigningKey() {
        RSAKey current = key;
        if (current == null) {
            synchronized (this) {
                current = key;
                if (current == null) {
                    key = current = loadKey();
                }
            }
        }
        return current;
    }

    private RSAKey loadKey() {
        if (privateKeyBase64.isBlank() || publicKeyBase64.isBlank()) {
            throw new IllegalStateException("JWT RSA 키가 설정되지 않았습니다.");
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64))
            );
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64))
            );
            if (publicKey.getModulus().bitLength() < 2048) {
                throw new IllegalStateException("JWT RSA 키는 2048비트 이상이어야 합니다.");
            }
            if (!privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalStateException("JWT 공개키와 개인키가 일치하지 않습니다.");
            }
            if (privateKey instanceof RSAPrivateCrtKey crtKey
                    && !crtKey.getPublicExponent().equals(publicKey.getPublicExponent())) {
                throw new IllegalStateException("JWT 공개키와 개인키가 일치하지 않습니다.");
            }
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(keyId)
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("JWT RSA 키를 읽을 수 없습니다.", exception);
        }
    }
}
