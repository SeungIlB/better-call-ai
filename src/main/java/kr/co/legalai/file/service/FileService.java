package kr.co.legalai.file.service;

import kr.co.legalai.file.dto.response.FileResponse;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

public interface FileService {
    FileResponse upload(UUID caseId, UUID idempotencyKey, MultipartFile file);
    FileResponse getFile(UUID caseId, UUID fileId);
}
