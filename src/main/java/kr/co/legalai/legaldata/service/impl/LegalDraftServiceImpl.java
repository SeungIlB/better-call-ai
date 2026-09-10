package kr.co.legalai.legaldata.service.impl;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.transaction.UserScopedTransaction;
import kr.co.legalai.file.repository.ConfirmedEvidenceRepository;
import kr.co.legalai.legaldata.dto.request.CaseEvidenceSearchRequest;
import kr.co.legalai.legaldata.dto.response.LegalDraftResponse;
import kr.co.legalai.legaldata.dto.response.EvidenceExcerptResponse;
import kr.co.legalai.legaldata.repository.GroundedAnswerRepository;
import kr.co.legalai.legaldata.service.CaseEvidenceSearchService;
import kr.co.legalai.legaldata.service.LegalDraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.List;
import java.util.ArrayList;
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
        return generate(caseId, List.of(fileId), request);
    }

    @Override
    public LegalDraftResponse generate(UUID caseId, List<UUID> fileIds, CaseEvidenceSearchRequest request) {
        if (fileIds == null || fileIds.isEmpty() || fileIds.size() > 5 || fileIds.stream().distinct().count() != fileIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (!slots.tryAcquire()) throw new BusinessException(ErrorCode.LEGAL_ANSWER_BUSY);
        try {
            var contexts = fileIds.stream().map(file -> search.search(caseId, file, request)).toList();
            var first = contexts.get(0);
            var combinedText = new StringBuilder();
            var observations = new ArrayList<String>();
            var sources = new ArrayList<kr.co.legalai.legaldata.dto.response.LegalEvidenceResponse>();
            for (int index = 0; index < contexts.size(); index++) {
                var context = contexts.get(index);
                combinedText.append("[자료 ").append(index + 1).append("]\n").append(context.evidence().text()).append("\n");
                if (context.evidence().visionJson() != null) observations.add(context.evidence().visionJson());
                sources.addAll(context.results().items());
            }
            var combinedEvidence = fileIds.size() == 1 ? first.evidence() : EvidenceExcerptResponse.builder().fileId(fileIds.get(0))
                    .revisionId(first.evidence().revisionId()).text(combinedText.toString())
                    .visionJson("[" + String.join(",", observations) + "]")
                    .start(0).end(combinedText.length()).totalLength(combinedText.length()).partial(false).build();
            var draft = sources.isEmpty()
                    ? new GroundedAnswerRepository.Draft("검색된 법률 근거가 없어 안내 초안을 만들지 못했습니다.",
                            List.of(), List.of("분쟁 상황이나 다른 확정 문서 발췌를 확인해 주세요."))
                    : generator.generate(request.query(), combinedEvidence, sources.stream().distinct().toList());
            transactions.execute(userId -> {
                int version = evidence.lockVersion(caseId).orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));
                if (version != first.caseVersion()) throw new BusinessException(ErrorCode.CASE_VERSION_CONFLICT);
                for (int index = 0; index < contexts.size(); index++) {
                    var context = contexts.get(index);
                    var current = evidence.findCurrent(caseId, fileIds.get(index));
                    if (current.isEmpty() || !current.get().revisionId().equals(context.evidence().revisionId())) {
                        throw new BusinessException(ErrorCode.CASE_VERSION_CONFLICT);
                    }
                }
                return true;
            });
            return LegalDraftResponse.builder().caseId(caseId).caseVersion(first.caseVersion()).evidence(combinedEvidence)
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
