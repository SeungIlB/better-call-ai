package kr.co.legalai.analysis.service.impl;

import kr.co.legalai.analysis.dto.request.AnalysisRequest;
import kr.co.legalai.analysis.dto.response.AnalysisResponse;
import kr.co.legalai.analysis.repository.SavedAnalysisRepository;
import kr.co.legalai.analysis.service.AnalysisService;
import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.response.PageResponse;
import kr.co.legalai.common.transaction.UserScopedTransaction;
import kr.co.legalai.file.repository.ConfirmedEvidenceRepository;
import kr.co.legalai.legaldata.dto.request.CaseEvidenceSearchRequest;
import kr.co.legalai.legaldata.service.LegalDraftService;
import kr.co.legalai.legaldata.service.impl.LawArticleParser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.List;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class AnalysisServiceImpl implements AnalysisService {
    private final UserScopedTransaction transactions;
    private final SavedAnalysisRepository repository;
    private final ConfirmedEvidenceRepository evidence;
    private final LegalDraftService generator;
    private final ObjectMapper mapper;

    @Override public AnalysisResponse create(UUID caseId, UUID key, AnalysisRequest request) {
        var fileIds = request == null ? List.<UUID>of() : request.selectedFileIds();
        if (key == null || request == null || request.fileId() == null || fileIds.size() > 5
                || fileIds.stream().anyMatch(java.util.Objects::isNull) || fileIds.stream().distinct().count() != fileIds.size()
                || request.query() == null || request.query().isBlank()
                || request.query().length() > 300 || request.expectedCaseVersion() < 1
                || (request.excerptStart() != null && request.excerptStart() < 0)) throw error(ErrorCode.VALIDATION_ERROR);
        String fingerprint = LawArticleParser.hash(mapper.writeValueAsString(request));
        UUID id = UUID.randomUUID();
        var reservation = tx(userId -> {
            int version = lock(caseId);
            repository.expire(caseId);
            String scopedKey = LawArticleParser.hash("analysis:" + userId + ":" + caseId + ":" + key);
            var previous = repository.byKey(caseId, scopedKey);
            if (previous.isPresent()) {
                if (!previous.get().fingerprint().equals(fingerprint)) throw error(ErrorCode.ANALYSIS_KEY_CONFLICT);
                return previous.get().response();
            }
            if (version != request.expectedCaseVersion()) throw error(ErrorCode.CASE_VERSION_CONFLICT);
            for (var fileId : fileIds) {
                var current = evidence.findCurrent(caseId, fileId).orElseThrow(() -> error(ErrorCode.FILE_NOT_FOUND));
                int start = request.excerptStart() == null ? 0 : request.excerptStart();
                if (start >= current.correctedText().length()) throw error(ErrorCode.VALIDATION_ERROR);
            }
            if (repository.running(caseId)) throw error(ErrorCode.ANALYSIS_BUSY);
            repository.reserve(id, caseId, scopedKey, fingerprint, request);
            return repository.get(caseId, id).orElseThrow();
        });
        if (!reservation.id().equals(id)) return reservation;
        try {
            var draft = generator.generate(caseId, fileIds, new CaseEvidenceSearchRequest(
                    request.query(), request.expectedCaseVersion(), request.excerptStart()));
            return tx(userId -> {
                if (lock(caseId) != request.expectedCaseVersion()) throw error(ErrorCode.CASE_VERSION_CONFLICT);
                for (var fileId : fileIds) {
                    if (evidence.findCurrent(caseId, fileId).isEmpty()) throw error(ErrorCode.CASE_VERSION_CONFLICT);
                }
                repository.expire(caseId);
                if (!repository.complete(caseId, id, draft, fileIds)) throw error(ErrorCode.ANALYSIS_FAILED);
                return repository.get(caseId, id).orElseThrow();
            });
        } catch (RuntimeException failure) {
            var code = failure instanceof BusinessException business ? business.getErrorCode() : ErrorCode.ANALYSIS_FAILED;
            tx(userId -> { repository.fail(caseId, id, code.code()); return true; });
            throw error(code);
        }
    }

    @Override public AnalysisResponse get(UUID caseId, UUID id) {
        return tx(user -> {
            lock(caseId); repository.expire(caseId);
            return repository.get(caseId, id).orElseThrow(() -> error(ErrorCode.ANALYSIS_NOT_FOUND));
        });
    }

    @Override public PageResponse<AnalysisResponse> list(UUID caseId, int page, int pageSize) {
        if (page < 1 || page > 10000 || pageSize < 1 || pageSize > 20) throw error(ErrorCode.VALIDATION_ERROR);
        return tx(user -> {
            lock(caseId); repository.expire(caseId);
            var rows = repository.list(caseId, page, pageSize);
            return new PageResponse<>(rows.subList(0, Math.min(pageSize, rows.size())), page, pageSize, rows.size() > pageSize);
        });
    }

    private int lock(UUID caseId) { return repository.lockCase(caseId).orElseThrow(() -> error(ErrorCode.CASE_NOT_FOUND)); }
    private BusinessException error(ErrorCode code) { return new BusinessException(code); }
    private <T> T tx(Function<UUID, T> action) {
        try { return transactions.execute(action); }
        catch (DataAccessException | TransactionException failure) { throw error(ErrorCode.ANALYSIS_FAILED); }
    }
}
