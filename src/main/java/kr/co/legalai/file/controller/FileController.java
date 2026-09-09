package kr.co.legalai.file.controller;

import lombok.RequiredArgsConstructor;

import kr.co.legalai.common.response.ApiResponse;
import kr.co.legalai.file.dto.response.FileResponse;
import kr.co.legalai.file.service.FileService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.net.URI;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cases/{caseId}/files")
public class FileController {
    private final FileService service;


    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<FileResponse>> upload(
            @PathVariable UUID caseId, @RequestPart("file") MultipartFile file
    ) {
        var response = service.upload(caseId, file);
        return ResponseEntity.created(URI.create("/api/v1/cases/" + caseId + "/files/" + response.id()))
                .body(ApiResponse.success(response));
    }

    @GetMapping("/{fileId}")
    public ApiResponse<FileResponse> getFile(@PathVariable UUID caseId, @PathVariable UUID fileId) {
        return ApiResponse.success(service.getFile(caseId, fileId));
    }
}
