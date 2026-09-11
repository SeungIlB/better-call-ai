package kr.co.legalai.auth.service.impl;

import kr.co.legalai.auth.repository.AuthRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthCleanupService {
    private final AuthRepository repository;

    @Scheduled(fixedDelayString = "${auth.login-attempt-cleanup-delay-ms:3600000}",
            initialDelayString = "${auth.login-attempt-cleanup-delay-ms:3600000}")
    public void cleanupStaleLoginAttempts() {
        try {
            repository.deleteStaleLoginAttempts();
        } catch (RuntimeException failure) {
            log.error("로그인 시도 기록 정리 작업 실패. 인증 데이터베이스 상태를 확인해야 합니다.");
        }
    }
}
