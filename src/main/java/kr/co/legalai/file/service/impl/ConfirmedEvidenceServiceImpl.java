package kr.co.legalai.file.service.impl;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.response.PageResponse;
import kr.co.legalai.common.transaction.UserScopedTransaction;
import kr.co.legalai.file.dto.response.EvidenceContextResponse;
import kr.co.legalai.file.repository.ConfirmedEvidenceRepository;
import kr.co.legalai.file.service.ConfirmedEvidenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConfirmedEvidenceServiceImpl implements ConfirmedEvidenceService {
    private final UserScopedTransaction transactions;
    private final ConfirmedEvidenceRepository repository;

    @Override
    public EvidenceContextResponse get(UUID caseId, int page, int pageSize, Integer expectedCaseVersion) {
        if (page < 1 || page > 10000 || pageSize < 1 || pageSize > 100
                || (expectedCaseVersion != null && expectedCaseVersion < 1)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return transactions.execute(userId -> {
            int version = repository.lockVersion(caseId).orElseThrow(() -> new BusinessException(ErrorCode.CASE_NOT_FOUND));
            if (expectedCaseVersion != null && expectedCaseVersion != version) {
                throw new BusinessException(ErrorCode.CASE_VERSION_CONFLICT);
            }
            var items = repository.findPage(caseId, page, pageSize);
            return EvidenceContextResponse.builder().caseId(caseId).caseVersion(version)
                    .evidence(new PageResponse<>(items.subList(0, Math.min(pageSize, items.size())), page, pageSize,
                            items.size() > pageSize)).build();
        });
    }
}
