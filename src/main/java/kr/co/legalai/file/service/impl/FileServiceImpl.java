package kr.co.legalai.file.service.impl;

import lombok.RequiredArgsConstructor;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.CaseNotFoundException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.transaction.UserScopedTransaction;
import kr.co.legalai.file.dto.response.FileResponse;
import kr.co.legalai.file.repository.FileRepository;
import kr.co.legalai.file.repository.LocalOriginalStorage;
import kr.co.legalai.file.service.FileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {
    private final UserScopedTransaction transaction;
    private final FileRepository repository;
    private final LocalOriginalStorage storage;
    private final FileValidator validator;


    @Override
    public FileResponse upload(UUID caseId, MultipartFile file) {
        return transaction.execute(userId -> {
            if (!repository.lockCase(caseId)) {
                throw new CaseNotFoundException();
            }
            var policy = repository.policy();
            String mime = validator.validateRequest(file, policy);
            if (!repository.isAllowedMime(mime)) {
                throw new BusinessException(ErrorCode.INVALID_FILE);
            }
            if (!repository.withinCaseLimit(caseId, file.getSize(), policy)) {
                throw new BusinessException(ErrorCode.FILE_CASE_LIMIT);
            }
            UUID fileId = UUID.randomUUID();
            var stored = storage.save(fileId, file, policy.maxBytes());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        storage.delete(fileId);
                    }
                }
            });
            int pages = validator.validateContent(stored.path(), mime, policy);
            if (!repository.withinCaseLimit(caseId, stored.sizeBytes(), policy)) {
                throw new BusinessException(ErrorCode.FILE_CASE_LIMIT);
            }
            repository.save(caseId, userId, stored, file.getOriginalFilename(), mime, pages, policy.retentionHours());
            repository.recordUploadEvent(caseId, fileId);
            return repository.find(caseId, fileId).orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));
        });
    }

    @Override
    public FileResponse getFile(UUID caseId, UUID fileId) {
        return transaction.execute(userId -> repository.find(caseId, fileId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND)));
    }
}
