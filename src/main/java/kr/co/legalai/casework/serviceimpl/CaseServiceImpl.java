package kr.co.legalai.casework.serviceimpl;

import kr.co.legalai.casework.dto.request.CreateCaseRequest;
import kr.co.legalai.casework.dto.request.UpdateCaseRequest;
import kr.co.legalai.casework.dto.response.CaseResponse;
import kr.co.legalai.casework.entity.CaseEntity;
import kr.co.legalai.casework.repository.AnalysisRepository;
import kr.co.legalai.casework.repository.CaseRepository;
import kr.co.legalai.casework.repository.OutboxRepository;
import kr.co.legalai.casework.service.CaseService;
import kr.co.legalai.common.exception.CaseNotFoundException;
import kr.co.legalai.common.exception.CaseVersionConflictException;
import kr.co.legalai.common.transaction.UserScopedTransaction;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 사용자 범위 트랜잭션에서 사건 변경, 분석 무효화, outbox 기록을 조정한다.
 */
@Service
public class CaseServiceImpl implements CaseService {
    private final UserScopedTransaction transaction;
    private final CaseRepository caseRepository;
    private final AnalysisRepository analysisRepository;
    private final OutboxRepository outboxRepository;

    public CaseServiceImpl(
            UserScopedTransaction transaction,
            CaseRepository caseRepository,
            AnalysisRepository analysisRepository,
            OutboxRepository outboxRepository
    ) {
        this.transaction = transaction;
        this.caseRepository = caseRepository;
        this.analysisRepository = analysisRepository;
        this.outboxRepository = outboxRepository;
    }

    @Override
    public CaseResponse createCase(CreateCaseRequest request) {
        return transaction.execute(userId -> {
            UUID caseId = UUID.randomUUID();
            caseRepository.save(caseId, userId, request);
            outboxRepository.save(
                    UUID.randomUUID(),
                    "CASE_CREATED",
                    caseId,
                    "case-created:" + caseId,
                    1
            );
            return toResponse(getRequiredCase(caseId));
        });
    }

    @Override
    public CaseResponse getCase(UUID caseId) {
        return transaction.execute(userId -> toResponse(getRequiredCase(caseId)));
    }

    @Override
    public CaseResponse updateCase(UUID caseId, UpdateCaseRequest request) {
        return transaction.execute(userId -> {
            int updatedRows = caseRepository.update(
                    caseId,
                    request.originalStatement(),
                    request.expectedVersion()
            );
            if (updatedRows == 0) {
                if (caseRepository.findById(caseId).isEmpty()) {
                    throw new CaseNotFoundException();
                }
                throw new CaseVersionConflictException();
            }

            int nextVersion = request.expectedVersion() + 1;
            analysisRepository.markAllCurrentRunsStale(caseId);
            outboxRepository.save(
                    UUID.randomUUID(),
                    "CASE_INPUT_CHANGED",
                    caseId,
                    "case-input-changed:" + caseId + ":" + nextVersion,
                    nextVersion
            );
            return toResponse(getRequiredCase(caseId));
        });
    }

    private CaseEntity getRequiredCase(UUID caseId) {
        return caseRepository.findById(caseId).orElseThrow(CaseNotFoundException::new);
    }

    private CaseResponse toResponse(CaseEntity entity) {
        return new CaseResponse(
                entity.id(),
                entity.title(),
                entity.status(),
                entity.userPartyRole(),
                entity.userGoal(),
                entity.originalStatement(),
                entity.version(),
                entity.createdAt(),
                entity.updatedAt()
        );
    }
}
