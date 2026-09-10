package kr.co.legalai.legaldata.service.impl;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.response.PageResponse;
import kr.co.legalai.common.security.AuthenticatedUser;
import kr.co.legalai.common.transaction.UserScopedTransaction;
import kr.co.legalai.legaldata.dto.response.LegalEvidenceResponse;
import kr.co.legalai.legaldata.repository.EmbeddingRepository;
import kr.co.legalai.legaldata.repository.LegalEvidenceSearchRepository;
import kr.co.legalai.legaldata.service.LegalEvidenceSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Semaphore;

@Service
@RequiredArgsConstructor
public class LegalEvidenceSearchServiceImpl implements LegalEvidenceSearchService {
    private final AuthenticatedUser user;
    private final UserScopedTransaction transactions;
    private final EmbeddingRepository embeddings;
    private final LegalEvidenceSearchRepository repository;
    private final Semaphore slots = new Semaphore(2);

    @Override
    public PageResponse<LegalEvidenceResponse> search(String query) {
        if (query == null || query.isBlank() || query.length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        user.getUserId();
        embeddings.requireConfigured();
        if (!slots.tryAcquire()) throw new BusinessException(ErrorCode.LEGAL_SEARCH_BUSY);
        try {
            // 외부 호출 중 DB 트랜잭션·연결을 점유하지 않는다.
            float[] vector = embeddings.embed(List.of(query.strip())).getFirst();
            var matches = transactions.execute(id -> repository.search(query.strip(), vector));
            return new PageResponse<>(matches, 1, 8, false);
        } catch (RuntimeException failure) {
            // JDBC 오류에도 검색어가 포함될 수 있으므로 원문·cause를 전역 로그로 전달하지 않는다.
            throw new BusinessException(ErrorCode.LEGAL_SEARCH_FAILED);
        } finally {
            slots.release();
        }
    }
}
