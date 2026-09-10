package kr.co.legalai.legaldata;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.response.PageResponse;
import kr.co.legalai.common.transaction.UserScopedTransaction;
import kr.co.legalai.file.dto.response.ConfirmedEvidenceResponse;
import kr.co.legalai.file.repository.ConfirmedEvidenceRepository;
import kr.co.legalai.legaldata.dto.request.CaseEvidenceSearchRequest;
import kr.co.legalai.legaldata.dto.response.*;
import kr.co.legalai.legaldata.repository.GroundedAnswerRepository;
import kr.co.legalai.legaldata.service.CaseEvidenceSearchService;
import kr.co.legalai.legaldata.service.impl.LegalDraftServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegalDraftServiceTest {
    private final CaseEvidenceSearchService search = mock(CaseEvidenceSearchService.class);
    private final GroundedAnswerRepository generator = mock(GroundedAnswerRepository.class);
    private final UserScopedTransaction tx = mock(UserScopedTransaction.class);
    private final ConfirmedEvidenceRepository evidence = mock(ConfirmedEvidenceRepository.class);
    private final LegalDraftServiceImpl service = new LegalDraftServiceImpl(search, generator, tx, evidence);
    private final UUID caseId = UUID.randomUUID(), fileId = UUID.randomUUID(), revision = UUID.randomUUID();
    private final CaseEvidenceSearchRequest request = new CaseEvidenceSearchRequest("집 수선", 2, 0);

    @BeforeEach void setup() {
        when(tx.execute(any())).thenAnswer(call -> {
            java.util.function.Function<UUID, ?> action = call.getArgument(0);
            return action.apply(UUID.randomUUID());
        });
        when(evidence.lockVersion(caseId)).thenReturn(Optional.of(2));
        when(evidence.findCurrent(caseId, fileId)).thenReturn(Optional.of(
                ConfirmedEvidenceResponse.builder().revisionId(revision).build()));
        when(generator.generate(anyString(), any(), anyList())).thenReturn(new GroundedAnswerRepository.Draft(
                "확인할 내용", List.of(GroundedFindingResponse.builder().explanation("검토 필요").build()), List.of()));
    }

    private void context(boolean empty) {
        when(search.search(caseId, fileId, request)).thenReturn(CaseEvidenceSearchResponse.builder()
                .caseId(caseId).caseVersion(2).evidence(EvidenceExcerptResponse.builder().revisionId(revision).build())
                .results(new PageResponse<>(empty ? List.of() : List.of(LegalEvidenceResponse.builder().build()), 1, 8, false)).build());
    }

    @Test void noSourcesSkipsGeneration() {
        context(true);
        assertEquals("INSUFFICIENT_EVIDENCE", service.generate(caseId, fileId, request).status());
        verifyNoInteractions(generator);
    }

    @Test void validDraftIsMarkedForReview() {
        context(false);
        var result = service.generate(caseId, fileId, request);
        assertEquals("NEEDS_REVIEW", result.status());
        assertEquals(revision, result.evidence().revisionId());
        assertFalse(result.notice().isBlank());
    }

    @Test void changedVersionRevisionOrDeletedCaseDiscardsDraft() {
        context(false);
        when(evidence.lockVersion(caseId)).thenReturn(Optional.of(3));
        assertEquals(ErrorCode.CASE_VERSION_CONFLICT, assertThrows(BusinessException.class,
                () -> service.generate(caseId, fileId, request)).getErrorCode());
        when(evidence.lockVersion(caseId)).thenReturn(Optional.of(2));
        when(evidence.findCurrent(caseId, fileId)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.CASE_VERSION_CONFLICT, assertThrows(BusinessException.class,
                () -> service.generate(caseId, fileId, request)).getErrorCode());
        when(evidence.lockVersion(caseId)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.CASE_NOT_FOUND, assertThrows(BusinessException.class,
                () -> service.generate(caseId, fileId, request)).getErrorCode());
    }
}
