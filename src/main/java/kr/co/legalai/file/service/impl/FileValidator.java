package kr.co.legalai.file.service.impl;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.file.entity.UploadPolicy;
import kr.co.legalai.file.entity.UploadFingerprint;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

@Component
public class FileValidator {
    private static final Map<String, String> TYPES = Map.of(
            "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png",
            "webp", "image/webp", "pdf", "application/pdf");

    public String validateRequest(MultipartFile file, UploadPolicy policy) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 255 || !name.equals(name.trim())
                || name.contains("/") || name.contains("\\") || name.contains(":")
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.INVALID_FILE);
        }
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mime = TYPES.get(extension);
        if (mime == null || !mime.equalsIgnoreCase(file.getContentType())) {
            throw new BusinessException(ErrorCode.INVALID_FILE);
        }
        if (file.getSize() > policy.maxBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_FILE);
        }
        return mime;
    }

    public UploadFingerprint fingerprint(UUID caseId, MultipartFile file, String mime, long maxBytes) {
        try (var input = file.getInputStream()) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long size = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > maxBytes) {
                    throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
                }
                digest.update(buffer, 0, read);
            }
            if (size == 0) {
                throw new BusinessException(ErrorCode.INVALID_FILE);
            }
            String contentHash = HexFormat.of().formatHex(digest.digest());
            String metadata = caseId + "\0" + file.getOriginalFilename() + "\0" + mime + "\0" + contentHash;
            return UploadFingerprint.builder().sha256(contentHash).sizeBytes(size)
                    .requestHash(HexFormat.of().formatHex(digest.digest(metadata.getBytes(StandardCharsets.UTF_8))))
                    .build();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.FILE_STORAGE_ERROR);
        }
    }

    public int validateContent(Path path, String mime, UploadPolicy policy) {
        try {
            byte[] header;
            try (var input = Files.newInputStream(path)) {
                header = input.readNBytes(12);
            }
            if (!matchesMagic(header, mime)) {
                throw new BusinessException(ErrorCode.INVALID_FILE);
            }
            if (mime.equals("application/pdf")) {
                try (var document = Loader.loadPDF(path.toFile())) {
                    if (document.isEncrypted()) {
                        throw new BusinessException(ErrorCode.INVALID_FILE);
                    }
                    int pages = 0;
                    for (var ignored : document.getPages()) {
                        if (++pages > policy.maxPdfPages()) {
                            throw new BusinessException(ErrorCode.FILE_PAGE_LIMIT);
                        }
                    }
                    if (pages == 0) {
                        throw new BusinessException(ErrorCode.FILE_PAGE_LIMIT);
                    }
                    return pages;
                }
            }
            try (var input = new FileImageInputStream(path.toFile())) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    throw new BusinessException(ErrorCode.INVALID_FILE);
                }
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 10_000 || height > 10_000
                            || (long) width * height > 20_000_000) {
                        throw new BusinessException(ErrorCode.INVALID_FILE);
                    }
                    var parameters = reader.getDefaultReadParam();
                    parameters.setSourceSubsampling(Math.max(1, width / 1024), Math.max(1, height / 1024), 0, 0);
                    var image = reader.read(0, parameters);
                    if (image == null) {
                        throw new BusinessException(ErrorCode.INVALID_FILE);
                    }
                    image.flush();
                } finally {
                    reader.dispose();
                }
            }
            return 1;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            // 파서 예외에는 문서 원문이 포함될 수 있으므로 전달하거나 로그로 남기지 않는다.
            throw new BusinessException(ErrorCode.INVALID_FILE);
        }
    }

    private boolean matchesMagic(byte[] header, String mime) {
        if (header.length < 12) {
            return false;
        }
        return switch (mime) {
            case "application/pdf" -> new String(header, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
            case "image/png" -> Arrays.equals(Arrays.copyOf(header, 8),
                    new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            case "image/jpeg" -> header[0] == (byte) 0xff && header[1] == (byte) 0xd8 && header[2] == (byte) 0xff;
            case "image/webp" -> new String(header, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(header, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
            default -> false;
        };
    }
}
