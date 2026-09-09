package kr.co.legalai.file.service.impl;

import lombok.RequiredArgsConstructor;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.CaseNotFoundException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.transaction.UserScopedTransaction;
import kr.co.legalai.file.dto.response.FileResponse;
import kr.co.legalai.file.repository.FileRepository;
import kr.co.legalai.file.repository.ClamAvRepository;
import kr.co.legalai.file.repository.UploadRequestRepository;
import kr.co.legalai.file.entity.UploadFingerprint;
import kr.co.legalai.file.entity.UploadPolicy;
import lombok.extern.slf4j.Slf4j;
import kr.co.legalai.file.repository.LocalOriginalStorage;
import kr.co.legalai.file.service.FileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileServiceImpl implements FileService {
    private final UserScopedTransaction transaction;
    private final FileRepository repository;
    private final LocalOriginalStorage storage;
    private final FileValidator validator;
    private final ClamAvRepository malwareScanner;
    private final UploadRequestRepository requests;


    @Override
    public FileResponse upload(UUID caseId, UUID idempotencyKey, MultipartFile file) {
        if (idempotencyKey == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        var policy = transaction.execute(userId -> {
            if (!repository.caseExists(caseId)) {
                throw new CaseNotFoundException();
            }
            var limits = repository.policy();
            if (!repository.isAllowedMime(validator.validateRequest(file, limits))) {
                throw new BusinessException(ErrorCode.INVALID_FILE);
            }
            return limits;
        });
        String mime = validator.validateRequest(file, policy);
        var fingerprint = validator.fingerprint(caseId, file, mime, policy.maxBytes());
        UUID fileId = UUID.randomUUID();
        boolean reserved = transaction.execute(userId -> {
            if (!repository.caseExists(caseId)) {
                throw new CaseNotFoundException();
            }
            if (!requests.reserve(userId, idempotencyKey, caseId, fingerprint.requestHash(), fileId)) {
                var existing = requests.find(idempotencyKey);
                if (!existing.fingerprint().equals(fingerprint.requestHash())) {
                    throw new BusinessException(ErrorCode.UPLOAD_KEY_CONFLICT);
                }
                if (existing.status().equals("FAILED")) {
                    throw new BusinessException(ErrorCode.valueOf(existing.errorCode()));
                }
                if (!existing.status().equals("COMPLETED")) {
                    throw new BusinessException(ErrorCode.UPLOAD_IN_PROGRESS);
                }
                return false;
            }
            if (!repository.withinCaseLimit(caseId, fingerprint.sizeBytes(), policy)) {
                throw new BusinessException(ErrorCode.FILE_CASE_LIMIT);
            }
            String result = requests.consume(fingerprint.sizeBytes());
            if ("PLAN_LIMIT".equals(result)) {
                throw new BusinessException(ErrorCode.UPLOAD_DAILY_LIMIT);
            }
            if (!"OK".equals(result)) {
                throw new IllegalStateException("알 수 없는 업로드 예약 결과입니다.");
            }
            return true;
        });
        if (!reserved) {
            return transaction.execute(userId -> repository.find(caseId, requests.find(idempotencyKey).fileId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND)));
        }
        try {
            return performUpload(caseId, idempotencyKey, fileId, file, fingerprint, policy, mime);
        } catch (RuntimeException exception) {
            ErrorCode code = exception instanceof BusinessException business
                    ? business.getErrorCode() : ErrorCode.INTERNAL_ERROR;
            try {
                transaction.execute(userId -> {
                    requests.finish(idempotencyKey, "FAILED", code.name());
                    return true;
                });
            } catch (RuntimeException recordingFailure) {
                log.error("업로드 실패 상태 기록 실패. 자동 재실행 없이 운영 확인이 필요합니다.");
            }
            throw exception;
        }
    }

    private FileResponse performUpload(UUID caseId, UUID key, UUID fileId, MultipartFile file,
                                       UploadFingerprint fingerprint, UploadPolicy policy, String mime) {
        return transaction.execute(userId -> {
            if (!repository.lockCase(caseId)) {
                throw new CaseNotFoundException();
            }
            if (!repository.withinCaseLimit(caseId, fingerprint.sizeBytes(), policy)) {
                throw new BusinessException(ErrorCode.FILE_CASE_LIMIT);
            }
            var stored = storage.save(fileId, file, policy.maxBytes());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        storage.delete(fileId);
                    }
                }
            });
            if (stored.sizeBytes() != fingerprint.sizeBytes() || !stored.sha256().equals(fingerprint.sha256())) {
                throw new BusinessException(ErrorCode.UPLOAD_KEY_CONFLICT);
            }
            malwareScanner.assertClean(stored.path());
            int pages = validator.validateContent(stored.path(), mime, policy);
            if (!repository.withinCaseLimit(caseId, stored.sizeBytes(), policy)) {
                throw new BusinessException(ErrorCode.FILE_CASE_LIMIT);
            }
            repository.save(caseId, userId, stored, file.getOriginalFilename(), mime, pages, policy.retentionHours());
            repository.markClean(fileId);
            repository.recordUploadEvent(caseId, fileId);
            requests.finish(key, "COMPLETED", null);
            return repository.find(caseId, fileId).orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));
        });
    }

    @Override
    public FileResponse getFile(UUID caseId, UUID fileId) {
        return transaction.execute(userId -> repository.find(caseId, fileId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND)));
    }
}
