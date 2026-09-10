package kr.co.legalai.file.repository;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.file.entity.StoredOriginal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Repository
@Slf4j
public class LocalOriginalStorage {
    private final Path root;

    public LocalOriginalStorage(@Value("${storage.local.root}") String root) throws IOException {
        Path configured = Path.of(root).toAbsolutePath().normalize();
        Files.createDirectories(configured);
        this.root = configured.toRealPath();
    }

    public StoredOriginal save(UUID fileId, MultipartFile source, long maxBytes) {
        Path target = path(fileId);
        boolean created = false;
        try {
            if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(target);
            }
            created = true;
            var digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var input = source.getInputStream(); var output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    if (size > maxBytes) {
                        throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (size == 0) {
                throw new BusinessException(ErrorCode.INVALID_FILE);
            }
            return StoredOriginal.builder().id(fileId).path(target).sizeBytes(size)
                    .sha256(HexFormat.of().formatHex(digest.digest())).build();
        } catch (Exception exception) {
            if (created) {
                delete(fileId);
            }
            if (exception instanceof BusinessException business) {
                throw business;
            }
            throw new BusinessException(ErrorCode.FILE_STORAGE_ERROR);
        }
    }

    public byte[] readForOcr(UUID fileId, long expectedSize, String expectedHash) {
        if (expectedSize < 1 || expectedSize > 20971520) {
            throw new BusinessException(ErrorCode.OCR_ORIGINAL_UNAVAILABLE);
        }
        try (var input = Files.newInputStream(path(fileId), LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes((int) expectedSize + 1);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (bytes.length != expectedSize || !hash.equals(expectedHash)) {
                throw new BusinessException(ErrorCode.OCR_ORIGINAL_UNAVAILABLE);
            }
            return bytes;
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            throw new BusinessException(ErrorCode.OCR_ORIGINAL_UNAVAILABLE);
        }
    }

    public boolean delete(UUID fileId) {
        try {
            Files.deleteIfExists(path(fileId));
            return !Files.exists(path(fileId), LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            log.warn("임시 원본 삭제 실패. fileId={}", fileId);
            return false;
        }
    }

    public List<UUID> expiredLocalIds(Instant before) {
        try (var files = Files.list(root)) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> file.getFileName().toString().matches(
                            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.upload"))
                    .filter(file -> olderThan(file, before))
                    .limit(100)
                    .map(file -> UUID.fromString(file.getFileName().toString().substring(0, 36)))
                    .toList();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.FILE_STORAGE_ERROR);
        }
    }

    private boolean olderThan(Path file, Instant before) {
        try {
            return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(before);
        } catch (IOException exception) {
            return false;
        }
    }

    private Path path(UUID fileId) {
        return root.resolve(fileId + ".upload");
    }
}
