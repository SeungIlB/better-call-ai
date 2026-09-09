package kr.co.legalai.file;

import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.file.entity.UploadPolicy;
import kr.co.legalai.file.repository.LocalOriginalStorage;
import kr.co.legalai.file.service.impl.FileValidator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class FileValidationTest {
    @TempDir
    Path root;

    private final FileValidator validator = new FileValidator();
    private final UploadPolicy policy = UploadPolicy.builder()
            .maxBytes(20L * 1024 * 1024).maxPdfPages(30).maxFiles(10)
            .maxCaseBytes(100L * 1024 * 1024).retentionHours(24).build();

    @Test
    void actualStreamSizeIsBoundedAndPartialFileIsDeleted() throws Exception {
        var storage = new LocalOriginalStorage(root.toString());
        UUID id = UUID.randomUUID();
        var file = new MockMultipartFile("file", "a.png", "image/png", new byte[20]) {
            @Override
            public long getSize() {
                return 1;
            }
        };
        var error = assertThrows(BusinessException.class, () -> storage.save(id, file, 10));
        assertEquals(ErrorCode.FILE_TOO_LARGE, error.getErrorCode());
        assertFalse(Files.exists(root.resolve(id + ".upload")));
    }

    @Test
    void failedCreationDoesNotDeleteExistingOriginal() throws Exception {
        var storage = new LocalOriginalStorage(root.toString());
        UUID id = UUID.randomUUID();
        Path existing = Files.write(root.resolve(id + ".upload"), new byte[]{1, 2, 3});
        assertThrows(BusinessException.class, () -> storage.save(id,
                new MockMultipartFile("file", new byte[]{4}), 10));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(existing));
    }

    @Test
    void encryptedPdfIsRejectedEvenWithEmptyUserPassword() throws Exception {
        Path path = root.resolve("encrypted.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.protect(new StandardProtectionPolicy("test-owner-password", "", new AccessPermission()));
            document.save(path.toFile());
        }
        var error = assertThrows(BusinessException.class,
                () -> validator.validateContent(path, "application/pdf", policy));
        assertEquals(ErrorCode.INVALID_FILE, error.getErrorCode());
    }

    @Test
    void jpegAndWebpAreDecodedRatherThanOnlyCheckingHeaders() throws Exception {
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpeg", output));
        Path jpeg = Files.write(root.resolve("test.jpg"), output.toByteArray());
        assertEquals(1, validator.validateContent(jpeg, "image/jpeg", policy));
        byte[] webp = Base64.getDecoder().decode(
                "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        Path webpPath = Files.write(root.resolve("test.webp"), webp);
        assertEquals(1, validator.validateContent(webpPath, "image/webp", policy));
        Path truncated = Files.write(root.resolve("broken.webp"), java.util.Arrays.copyOf(webp, 12));
        assertThrows(BusinessException.class, () -> validator.validateContent(truncated, "image/webp", policy));
    }

    @Test
    void builderCannotBypassPolicyValidation() {
        assertThrows(IllegalStateException.class, () -> UploadPolicy.builder()
                .maxBytes(1).maxPdfPages(31).maxFiles(10).maxCaseBytes(100).retentionHours(24).build());
    }
}
