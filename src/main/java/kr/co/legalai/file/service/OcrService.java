package kr.co.legalai.file.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import kr.co.legalai.common.response.PageResponse;
import kr.co.legalai.file.dto.request.*;
import kr.co.legalai.file.dto.response.*;
import java.util.UUID;

public interface OcrService {
    OcrResponse extract(UUID caseId, UUID fileId, @NotNull UUID key, @NotNull @Valid StartOcrRequest request);
    OcrResponse get(UUID caseId, UUID fileId);
    OcrRevisionResponse save(UUID caseId, UUID fileId, @NotNull @Valid SaveOcrRevisionRequest request);
    PageResponse<OcrRevisionResponse> history(UUID caseId, UUID fileId, int page, int pageSize);
    OcrRevisionResponse confirm(UUID caseId, UUID fileId, @NotNull @Valid ConfirmOcrRequest request);
}
