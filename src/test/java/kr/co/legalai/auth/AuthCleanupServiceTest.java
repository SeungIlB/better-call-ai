package kr.co.legalai.auth;

import kr.co.legalai.auth.repository.AuthRepository;
import kr.co.legalai.auth.service.impl.AuthCleanupService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class AuthCleanupServiceTest {
    @Test
    void deletesStaleLoginAttemptsWithoutExposingRows() {
        var repository = mock(AuthRepository.class);
        new AuthCleanupService(repository).cleanupStaleLoginAttempts();
        verify(repository).deleteStaleLoginAttempts();
    }
}
