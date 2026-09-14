package kr.co.legalai.common.security;

import kr.co.legalai.auth.security.AccessTokenBlacklist;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class RevokedAccessTokenValidator implements OAuth2TokenValidator<Jwt> {
    private final AccessTokenBlacklist blacklist;
    public RevokedAccessTokenValidator(AccessTokenBlacklist blacklist) { this.blacklist = blacklist; }
    @Override public OAuth2TokenValidatorResult validate(Jwt token) {
        return blacklist.isRevoked(token.getId())
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "로그아웃된 Access Token입니다.", null))
                : OAuth2TokenValidatorResult.success();
    }
}
