package kr.co.legalai.auth.controller;

import com.nimbusds.jose.jwk.JWKSet;
import kr.co.legalai.auth.security.JwtKeyProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class JwkSetController {
    private final JwtKeyProvider keyProvider;

    public JwkSetController(JwtKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getKeys() {
        return new JWKSet(keyProvider.getSigningKey().toPublicJWK()).toJSONObject();
    }
}
