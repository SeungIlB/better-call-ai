package kr.co.legalai.legaldata;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.exception.IntegrationNotConfiguredException;
import kr.co.legalai.common.security.AuthenticatedUser;
import kr.co.legalai.common.transaction.UserScopedTransaction;
import kr.co.legalai.legaldata.repository.EmbeddingRepository;
import kr.co.legalai.legaldata.repository.LegalEvidenceSearchRepository;
import kr.co.legalai.legaldata.service.impl.LegalEvidenceSearchServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegalEvidenceSearchServiceTest {
    private final EmbeddingRepository embeddings = mock(EmbeddingRepository.class);
    private final UserScopedTransaction transactions = mock(UserScopedTransaction.class);
    private final LegalEvidenceSearchRepository repository = mock(LegalEvidenceSearchRepository.class);
    private final LegalEvidenceSearchServiceImpl service = new LegalEvidenceSearchServiceImpl(
            mock(AuthenticatedUser.class), transactions, embeddings, repository);

    @Test void rejectsInvalidQueryBeforeExternalCall() {
        for (String query : new String[]{null, " ", "가".repeat(1001)}) {
            assertEquals(ErrorCode.VALIDATION_ERROR,
                    assertThrows(BusinessException.class, () -> service.search(query)).getErrorCode());
        }
        verifyNoInteractions(embeddings, transactions, repository);
    }

    @Test void missingConfigurationAndProviderFailureAreDistinctAndSanitized() {
        doThrow(new IntegrationNotConfiguredException()).when(embeddings).requireConfigured();
        assertEquals(ErrorCode.INTEGRATION_NOT_CONFIGURED,
                assertThrows(BusinessException.class, () -> service.search("수선 비용")).getErrorCode());
        doNothing().when(embeddings).requireConfigured();
        when(embeddings.embed(anyList())).thenThrow(new IllegalStateException("민감한 검색 본문"));
        for (int i = 0; i < 3; i++) {
            var failure = assertThrows(BusinessException.class, () -> service.search("수선 비용"));
            assertEquals(ErrorCode.LEGAL_SEARCH_FAILED, failure.getErrorCode());
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("민감"));
        }
        verifyNoInteractions(transactions, repository);
        verify(embeddings, times(3)).embed(anyList());
    }
}
