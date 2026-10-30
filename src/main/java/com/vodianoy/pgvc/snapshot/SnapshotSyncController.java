package com.vodianoy.pgvc.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vodianoy.pgvc.vcs.CommitResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Reference snapshot files for isolated sites:
 * <ul>
 *   <li>{@code GET  /api/vc/snapshot/export?envId=&commitHash=} - download {@code snapshot_<branch>_<hash>.pgsnap.json};</li>
 *   <li>{@code POST /api/vc/snapshot/compare} - {envId, bundle}: live database vs the snapshot;</li>
 *   <li>{@code POST /api/vc/snapshot/apply} - run the reviewed SQL and commit it.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/vc/snapshot")
@RequiredArgsConstructor
public class SnapshotSyncController {

    private final SnapshotSyncService snapshots;
    private final ObjectMapper objectMapper;

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam("envId") String envId,
                                         @RequestParam(value = "commitHash", required = false) String commitHash)
            throws JsonProcessingException {
        SnapshotExport export = snapshots.export(envId, commitHash);
        byte[] body = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsBytes(export.bundle());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @PostMapping("/compare")
    public SnapshotComparisonResponse compare(@RequestBody SnapshotCompareRequest request) {
        return snapshots.compare(request);
    }

    @PostMapping("/apply")
    public CommitResult apply(@RequestBody SnapshotApplyRequest request) {
        return snapshots.apply(request);
    }
}
