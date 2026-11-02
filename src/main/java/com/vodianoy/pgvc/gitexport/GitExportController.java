package com.vodianoy.pgvc.gitexport;

import com.vodianoy.pgvc.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** Schema as a Git folder tree ("1 object = 1 file"). */
@RestController
@RequestMapping("/api/vc")
@RequiredArgsConstructor
public class GitExportController {

    private final GitDirectoryExporterService exporter;

    /**
     * With {@code targetPath}: writes into that local folder and returns a {@link GitExportResult}; without it:
     * returns the export as a .zip download.
     */
    @PostMapping("/git-export")
    public ResponseEntity<?> export(@RequestBody GitExportRequest request) {
        if (request == null) {
            throw ApiException.badRequest("env.idRequired");
        }
        boolean migrations = request.includeMigrations() == null || request.includeMigrations();
        GitExport export = exporter.render(request.envId(), request.commitHash(), migrations);
        if (request.targetPath() != null && !request.targetPath().isBlank()) {
            return ResponseEntity.ok(exporter.writeTo(export, request.targetPath()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.zipName(), StandardCharsets.UTF_8).build().toString())
                .body(exporter.zip(export));
    }
}
