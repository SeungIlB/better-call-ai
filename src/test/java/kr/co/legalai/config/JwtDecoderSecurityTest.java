package kr.co.legalai.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtDecoderSecurityTest {
    private static final String ISSUER = "https://issuer.test";
    private static final String AUDIENCE = "better-call-ai";
    private static final String KEY_ID = "test-key";

    private HttpServer server;
    private RSAKey signingKey;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        signingKey = generateKey(KEY_ID);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/jwks", this::respondWithJwks);
        server.start();
        decoder = new SecurityConfig().jwtDecoder(
                "http://localhost:" + server.getAddress().getPort() + "/jwks",
                ISSUER,
                AUDIENCE
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void acceptsValidSignedToken() throws Exception {
        var decoded = decoder.decode(token(signingKey, ISSUER, AUDIENCE, Instant.now().plusSeconds(300)));

        assertEquals("11111111-1111-1111-1111-111111111111", decoded.getSubject());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = token(signingKey, ISSUER, AUDIENCE, Instant.now().minusSeconds(60));

        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        String token = token(signingKey, "https://attacker.test", AUDIENCE, Instant.now().plusSeconds(300));

        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        String token = token(signingKey, ISSUER, "another-api", Instant.now().plusSeconds(300));

        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    void rejectsForgedSignature() throws Exception {
        RSAKey attackerKey = generateKey(KEY_ID);
        String token = token(attackerKey, ISSUER, AUDIENCE, Instant.now().plusSeconds(300));

        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    private String token(RSAKey key, String issuer, String audience, Instant expiresAt) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .subject("11111111-1111-1111-1111-111111111111")
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now.minusSeconds(1)))
                .notBeforeTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(expiresAt))
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(),
                claims
        );
        jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
        return jwt.serialize();
    }

    private RSAKey generateKey(String keyId) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(keyId)
                .build();
    }

    private void respondWithJwks(HttpExchange exchange) throws IOException {
        byte[] response = new JWKSet(signingKey.toPublicJWK())
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
