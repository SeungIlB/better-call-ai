package kr.co.legalai.legaldata.service.impl;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.transaction.UserScopedTransaction;
import kr.co.legalai.file.repository.ConfirmedEvidenceRepository;
import kr.co.legalai.legaldata.dto.request.CaseEvidenceSearchRequest;
import kr.co.legalai.legaldata.dto.response.LegalDraftResponse;
import kr.co.legalai.legaldata.repository.GroundedAnswerRepository;
import kr.co.legalai.legaldata.service.CaseEvidenceSearchService;
import kr.co.legalai.legaldata.service.LegalDraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
@RequiredArgsConstructor
public class LegalDraftServiceImpl implements LegalDraftService {
    private final CaseEvidenceSearchService search;
    private final GroundedAnswerRepository generator;
    private final UserScopedTransaction transactions;
    private final ConfirmedEvidenceRepository evidence;
    private final Semaphore slots = new Semaphore(2);

    @Override
    public LegalDraftResponse generate(UUID caseId, UUID fileId, CaseEvidenceSearchRequest request) {
        if (!slots.tryAcquire()) throw new BusinessException(ErrorCode.LEGAL_ANSWER_BUSY);
        try {
            var context = search.search(caseId, fileId, request);
            var draft = context.results().items().isEmpty()
                    ? new GroundedAnswerRepository.Draft("검색된 법률 근거가 없어 안내 초안을 만들지 못했습니다.",
                            List.of(), List.of("분쟁 상황이나 다른 확정 문서 발췌를 확인해 주세요."))
                    : generator.generate(request.query(), context.evidence(), context.results().items());
            transactions.execute(userId -> {
                int version = evidence.lockVersion(caseId).orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));
                if (version != context.caseVersion()) throw new BusinessException(ErrorCode.CASE_VERSION_CONFLICT);
                var current = evidence.findCurrent(caseId, fileId);
                if (current.isEmpty() || !current.get().revisionId().equals(context.evidence().revisionId())) {
                    throw new BusinessException(ErrorCode.CASE_VERSION_CONFLICT);
                }
                return true;
            });
            return LegalDraftResponse.builder().caseId(caseId).caseVersion(context.caseVersion()).evidence(context.evidence())
                    .status(draft.findings().isEmpty() ? "INSUFFICIENT_EVIDENCE" : "NEEDS_REVIEW")
                    .summary(draft.summary()).findings(draft.findings()).questions(draft.questions())
                    .notice("발췌문과 검색된 현행 법령에 근거한 검토용 초안입니다. 사건 당시 법령과 사실관계 확인이 필요합니다.").build();
        } catch (DataAccessException | TransactionException failure) {
            throw new BusinessException(ErrorCode.LEGAL_ANSWER_FAILED);
        } finally {
            slots.release();
        }
    }
}
