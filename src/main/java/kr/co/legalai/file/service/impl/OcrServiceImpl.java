package kr.co.legalai.file.service.impl;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.response.PageResponse;
import kr.co.legalai.common.transaction.UserScopedTransaction;
import kr.co.legalai.file.dto.request.*;
import kr.co.legalai.file.dto.response.*;
import kr.co.legalai.file.entity.*;
import kr.co.legalai.file.repository.*;
import kr.co.legalai.file.service.OcrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
@Validated
@RequiredArgsConstructor
@Slf4j
public class OcrServiceImpl implements OcrService {
    private final UserScopedTransaction transactions;
    private final FileRepository files;
    private final OcrRepository repository;
    private final OpenAiOcrRepository openAi;
    private final LocalOriginalStorage storage;
    private final FileCleanupRepository cleanup;
    private final Semaphore slots = new Semaphore(2);

    @Override
    public OcrResponse extract(UUID caseId, UUID fileId, UUID key, StartOcrRequest request) {
        Reservation reservation = transactions.execute(userId -> {
            OcrFile file = lock(caseId, fileId);
            repository.expire(fileId);
            var previous = repository.byKey(fileId, key);
            if (previous.isPresent()) {
                if (previous.get().status().equals("running")) throw error(ErrorCode.OCR_BUSY);
                if (!previous.get().status().equals("succeeded")) throw error(ErrorCode.OCR_UNAVAILABLE);
                return new Reservation(file, previous.get().id(), false);
            }
            if (file.extractionId() != null) {
                var current = repository.extraction(fileId, file.extractionId()).orElseThrow();
                if (current.status().equals("running")) throw error(ErrorCode.OCR_BUSY);
                if (current.status().equals("succeeded")) throw error(ErrorCode.OCR_CONFLICT);
            }
            if (!file.available()) throw error(ErrorCode.OCR_ORIGINAL_UNAVAILABLE);
            openAi.requireConfigured();
            UUID id = UUID.randomUUID();
            repository.reserve(fileId, id, key, openAi.model(), userId);
            return new Reservation(file, id, true);
        });
        if (!reservation.execute()) return get(caseId, fileId);
        if (!slots.tryAcquire()) {
            fail(caseId, fileId, reservation.id());
            throw error(ErrorCode.OCR_BUSY);
        }
        try {
            byte[] original = storage.readForOcr(fileId, reservation.file().sizeBytes(), reservation.file().sha256());
            OcrText text = openAi.extract(original, reservation.file().mimeType());
            return transactions.execute(userId -> {
                OcrFile file = lock(caseId, fileId);
                if (!file.available() || !reservation.id().equals(file.extractionId())) {
                    throw error(ErrorCode.OCR_ORIGINAL_UNAVAILABLE);
                }
                if (!repository.complete(fileId, reservation.id(), text)) throw error(ErrorCode.OCR_UNAVAILABLE);
                return view(file);
            });
        } catch (BusinessException failure) {
            fail(caseId, fileId, reservation.id());
            throw failure;
        } catch (RuntimeException failure) {
            fail(caseId, fileId, reservation.id());
            throw error(ErrorCode.OCR_UNAVAILABLE);
        } finally {
            slots.release();
        }
    }

    @Override
    public OcrResponse get(UUID caseId, UUID fileId) {
        return transactions.execute(userId -> {
            OcrFile file = lock(caseId, fileId);
            repository.expire(fileId);
            return view(file);
        });
    }

    @Override
    public OcrRevisionResponse save(UUID caseId, UUID fileId, SaveOcrRevisionRequest request) {
        return transactions.execute(userId -> {
            OcrFile file = lock(caseId, fileId);
            if (!request.extractionId().equals(file.extractionId())) throw error(ErrorCode.OCR_CONFLICT);
            requireResult(file);
            int latest = repository.latest(fileId).map(OcrRevisionResponse::revision).orElse(0);
            int next = request.expectedRevision() + 1;
            if (request.expectedRevision() < latest) {
                var previous = repository.revisionNumber(fileId, file.extractionId(), next);
                if (previous.isPresent() && previous.get().correctedText().equals(request.correctedText())) {
                    return previous.get();
                }
                throw error(ErrorCode.OCR_CONFLICT);
            }
            if (request.expectedRevision() != latest || latest >= 10000) throw error(ErrorCode.OCR_CONFLICT);
            return repository.save(fileId, file.extractionId(), next, request.correctedText(), userId);
        });
    }

    @Override
    public PageResponse<OcrRevisionResponse> history(UUID caseId, UUID fileId, int page, int pageSize) {
        if (page < 1 || page > 10000 || pageSize < 1 || pageSize > 100) throw error(ErrorCode.VALIDATION_ERROR);
        return transactions.execute(userId -> {
            lock(caseId, fileId);
            var items = repository.history(fileId, page, pageSize);
            return new PageResponse<>(items.subList(0, Math.min(items.size(), pageSize)), page, pageSize, items.size() > pageSize);
        });
    }

    @Override
    public OcrRevisionResponse confirm(UUID caseId, UUID fileId, ConfirmOcrRequest request) {
        Confirmation result = transactions.execute(userId -> {
            OcrFile file = lock(caseId, fileId);
            requireResult(file);
            var revision = repository.revision(fileId, request.revisionId()).orElseThrow(() -> error(ErrorCode.OCR_CONFLICT));
            if (revision.id().equals(file.revisionId())) return new Confirmation(revision, false);
            if (!repository.latest(fileId).map(OcrRevisionResponse::id).orElseThrow().equals(revision.id())) {
                throw error(ErrorCode.OCR_CONFLICT);
            }
            repository.confirm(caseId, file, revision.id());
            return new Confirmation(repository.revision(fileId, revision.id()).orElseThrow(), repository.purgeDue(fileId));
        });
        if (result.purge()) {
            // 수정본 확정 커밋 후에만 원본을 삭제한다. 실패해도 확정본은 유지하며 정리 작업이 재시도한다.
            try {
                cleanup.record(fileId, storage.delete(fileId));
            } catch (RuntimeException failure) {
                log.error("OCR 확정 후 원본 삭제 기록 실패. 정리 작업에서 재확인합니다. fileId={}", fileId);
            }
        }
        return result.revision();
    }

    private OcrFile lock(UUID caseId, UUID fileId) {
        if (!files.lockCase(caseId)) throw error(ErrorCode.FILE_NOT_FOUND);
        return repository.lockFile(caseId, fileId).orElseThrow(() -> error(ErrorCode.FILE_NOT_FOUND));
    }

    private OcrExtraction requireResult(OcrFile file) {
        if (file.extractionId() == null) throw error(ErrorCode.OCR_NOT_READY);
        var extraction = repository.extraction(file.id(), file.extractionId()).orElseThrow();
        if (!extraction.status().equals("succeeded")) throw error(ErrorCode.OCR_NOT_READY);
        return extraction;
    }

    private OcrResponse view(OcrFile file) {
        if (file.extractionId() == null) throw error(ErrorCode.OCR_NOT_READY);
        var extraction = repository.extraction(file.id(), file.extractionId()).orElseThrow();
        return OcrResponse.builder().fileId(file.id()).extractionId(extraction.id()).status(extraction.status())
                .rawText(extraction.rawText()).latestRevision(repository.latest(file.id()).orElse(null))
                .confirmedRevision(file.revisionId() == null ? null : repository.revision(file.id(), file.revisionId()).orElse(null))
                .build();
    }

    private void fail(UUID caseId, UUID fileId, UUID id) {
        try {
            transactions.execute(userId -> {
                lock(caseId, fileId);
                repository.fail(fileId, id);
                return true;
            });
        } catch (RuntimeException failure) {
            log.warn("OCR 실패 상태를 기록하지 못했습니다. fileId={}", fileId);
        }
    }

    private BusinessException error(ErrorCode code) { return new BusinessException(code); }
    private record Reservation(OcrFile file, UUID id, boolean execute) {}
    private record Confirmation(OcrRevisionResponse revision, boolean purge) {}
}
