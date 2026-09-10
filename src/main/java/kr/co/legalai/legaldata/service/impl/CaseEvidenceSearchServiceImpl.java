package kr.co.legalai.legaldata.service.impl;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.transaction.UserScopedTransaction;
import kr.co.legalai.file.dto.response.ConfirmedEvidenceResponse;
import kr.co.legalai.file.repository.ConfirmedEvidenceRepository;
import kr.co.legalai.legaldata.dto.request.CaseEvidenceSearchRequest;
import kr.co.legalai.legaldata.dto.response.CaseEvidenceSearchResponse;
import kr.co.legalai.legaldata.dto.response.EvidenceExcerptResponse;
import kr.co.legalai.legaldata.service.CaseEvidenceSearchService;
import kr.co.legalai.legaldata.service.LegalEvidenceSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CaseEvidenceSearchServiceImpl implements CaseEvidenceSearchService {
    private final UserScopedTransaction transactions;
    private final ConfirmedEvidenceRepository evidence;
    private final LegalEvidenceSearchService search;

    @Override
    public CaseEvidenceSearchResponse search(UUID caseId, UUID fileId, CaseEvidenceSearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank() || request.query().length() > 300
                || request.expectedCaseVersion() < 1 || (request.excerptStart() != null && request.excerptStart() < 0)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            var excerpt = transactions.execute(userId -> {
                checkVersion(caseId, request.expectedCaseVersion());
                var current = evidence.findCurrent(caseId, fileId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));
                return excerpt(current, request.excerptStart() == null ? 0 : request.excerptStart());
            });
            // 소유권 확인 트랜잭션 종료 후 외부 호출. 원본 파일·미확정 초안은 읽지 않는다.
            var results = search.search(request.query().strip() + "\n확정 문서 발췌:\n" + excerpt.text());
            transactions.execute(userId -> {
                checkVersion(caseId, request.expectedCaseVersion());
                var current = evidence.findCurrent(caseId, fileId);
                if (current.isEmpty() || !current.get().revisionId().equals(excerpt.revisionId())) {
                    throw new BusinessException(ErrorCode.CASE_VERSION_CONFLICT);
                }
                return true;
            });
            return CaseEvidenceSearchResponse.builder().caseId(caseId).caseVersion(request.expectedCaseVersion())
                    .evidence(excerpt).results(results).build();
        } catch (DataAccessException | TransactionException failure) {
            // SQL 예외에 OCR 본문이 포함될 수 있으므로 원문과 cause를 전달하지 않는다.
            throw new BusinessException(ErrorCode.LEGAL_SEARCH_FAILED);
        }
    }

    private void checkVersion(UUID caseId, int expected) {
        int actual = evidence.lockVersion(caseId).orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));
        if (actual != expected) throw new BusinessException(ErrorCode.CASE_VERSION_CONFLICT);
    }

    private EvidenceExcerptResponse excerpt(ConfirmedEvidenceResponse current, int start) {
        String text = current.correctedText();
        if (start >= text.length() || (start > 0 && Character.isLowSurrogate(text.charAt(start))
                && Character.isHighSurrogate(text.charAt(start - 1)))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        int end = Math.min(text.length(), start + 650);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        return EvidenceExcerptResponse.builder().fileId(current.fileId()).revisionId(current.revisionId())
                .text(text.substring(start, end)).start(start).end(end).totalLength(text.length())
                .partial(start > 0 || end < text.length()).build();
    }
}
