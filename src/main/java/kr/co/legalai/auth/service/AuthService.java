package kr.co.legalai.auth.service;

import kr.co.legalai.auth.dto.request.LoginRequest;
import kr.co.legalai.auth.dto.request.RegisterRequest;
import kr.co.legalai.auth.dto.response.AuthTokenResponse;
import kr.co.legalai.auth.dto.response.UserResponse;

public interface AuthService {
    AuthTokenResponse register(RegisterRequest request);

    AuthTokenResponse login(LoginRequest request);

    AuthTokenResponse refresh(String refreshToken);

    void logout(String refreshToken);

    UserResponse getMe();
}
