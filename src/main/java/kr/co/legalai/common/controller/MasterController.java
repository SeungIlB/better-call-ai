package kr.co.legalai.common.controller;

import kr.co.legalai.common.response.ApiResponse;
import kr.co.legalai.common.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/master")
public class MasterController {
    private final AuthenticatedUser user;
    public MasterController(AuthenticatedUser user) { this.user = user; }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        user.requireMaster();
        return ApiResponse.success(Map.of("role", "MASTER", "active", true));
    }
}
