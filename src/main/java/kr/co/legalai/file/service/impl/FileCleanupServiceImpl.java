package kr.co.legalai.file.service.impl;

import lombok.RequiredArgsConstructor;

import kr.co.legalai.file.repository.FileCleanupRepository;
import kr.co.legalai.file.repository.LocalOriginalStorage;
import kr.co.legalai.file.service.FileCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.Duration;

@Service
@Slf4j
@RequiredArgsConstructor
public class FileCleanupServiceImpl implements FileCleanupService {
    private final FileCleanupRepository repository;
    private final LocalOriginalStorage storage;


    @Override
    @Scheduled(fixedDelayString = "${storage.local.cleanup-delay-ms:60000}",
            initialDelayString = "${storage.local.cleanup-delay-ms:60000}")
    public void cleanup() {
        try {
            for (var fileId : repository.candidates()) {
                repository.record(fileId, storage.delete(fileId));
            }
            // DB 커밋 전 프로세스 종료 등으로 남은 원본은 24시간 이후 정리한다.
            for (var fileId : storage.expiredLocalIds(Instant.now().minus(Duration.ofHours(24)))) {
                if (!repository.isTracked(fileId)) {
                    storage.delete(fileId);
                }
            }
        } catch (RuntimeException exception) {
            log.error("임시 원본 정리 작업 실패. 저장소 및 데이터베이스 상태를 확인해야 합니다.");
        }
    }
}
