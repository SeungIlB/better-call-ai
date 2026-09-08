package kr.co.legalai.casework.application;

import kr.co.legalai.casework.api.CaseResponse;
import kr.co.legalai.casework.api.CreateCaseRequest;
import kr.co.legalai.casework.api.UpdateStatementRequest;
import kr.co.legalai.casework.domain.CaseRecord;
import kr.co.legalai.casework.persistence.CaseRepository;
import kr.co.legalai.common.persistence.UserScopedTransaction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class CaseService {
    private final UserScopedTransaction transaction;
    private final CaseRepository cases;

    public CaseService(UserScopedTransaction transaction, CaseRepository cases) {
        this.transaction = transaction;
        this.cases = cases;
    }

    public CaseResponse create(CreateCaseRequest request) {
        return transaction.execute(userId -> {
            UUID caseId = UUID.randomUUID();
            cases.insert(caseId, userId, request);
            cases.enqueue(
                    UUID.randomUUID(),
                    "CASE_CREATED",
                    caseId,
                    "case-created:" + caseId,
                    1
            );
            return toResponse(require(caseId));
        });
    }

    public CaseResponse get(UUID caseId) {
        return transaction.execute(userId -> toResponse(require(caseId)));
    }

    public CaseResponse updateStatement(UUID caseId, UpdateStatementRequest request) {
        return transaction.execute(userId -> {
            int updated = cases.updateStatement(caseId, request.statement(), request.expectedVersion());
            if (updated == 0) {
                if (cases.find(caseId).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사건을 찾을 수 없습니다.");
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT, "사건이 다른 요청에 의해 변경되었습니다.");
            }
            int nextVersion = request.expectedVersion() + 1;
            cases.markAnalysisStale(caseId);
            cases.enqueue(
                    UUID.randomUUID(),
                    "CASE_INPUT_CHANGED",
                    caseId,
                    "case-input-changed:" + caseId + ":" + nextVersion,
                    nextVersion
            );
            return toResponse(require(caseId));
        });
    }

    private CaseRecord require(UUID id) {
        return cases.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사건을 찾을 수 없습니다."));
    }

    private CaseResponse toResponse(CaseRecord record) {
        return new CaseResponse(
                record.id(),
                record.title(),
                record.status(),
                record.userPartyRole(),
                record.userGoal(),
                record.originalStatement(),
                record.version(),
                record.createdAt(),
                record.updatedAt()
        );
    }
}
