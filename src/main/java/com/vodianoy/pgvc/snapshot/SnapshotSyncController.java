package com.vodianoy.pgvc.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import com.vodianoy.pgvc.common.ApiException;
import com.vodianoy.pgvc.diff.DiffIgnoreRules;
import java.io.IOException;
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
 *   <li>{@code POST /api/vc/snapshot/compare} - {envId, bundle} (JSON) or envId + file (multipart): live database
 *       vs the snapshot;</li>
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

    /**
     * JSON body {@code {envId, bundle, ignoreRules}}; {@code ?envId=} may be given instead of the body field. The UI
     * sends the text of the file unchanged (no JavaScript number rounding).
     */
    @PostMapping(value = "/compare", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SnapshotComparisonResponse compare(@RequestParam(value = "envId", required = false) String envId,
                                              @RequestBody SnapshotCompareRequest request) {
        if (request == null) {
            throw ApiException.badRequest("snapshot.missingSchema");
        }
        String env = request.envId() == null || request.envId().isBlank() ? envId : request.envId();
        return snapshots.compare(new SnapshotCompareRequest(env, request.bundle(), request.ignoreRules()));
    }

    /**
     * The same as a file upload ({@code multipart/form-data}): {@code envId}, {@code file} (the .pgsnap.json) and an
     * optional {@code ignoreRules} part (JSON). For curl / scripts:
     * {@code curl -F envId=customer -F file=@snapshot.pgsnap.json .../api/vc/snapshot/compare}.
     */
    @PostMapping(value = "/compare", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SnapshotComparisonResponse compareUpload(@RequestParam("envId") String envId,
                                                    @RequestPart("file") MultipartFile file,
                                                    @RequestParam(value = "ignoreRules", required = false) String ignoreRules)
            throws IOException {
        SnapshotBundleDto bundle = readBundle(file.getBytes(), file.getOriginalFilename());
        DiffIgnoreRules rules = ignoreRules == null || ignoreRules.isBlank() ? null
                : objectMapper.readValue(ignoreRules, DiffIgnoreRules.class);
        return snapshots.compare(new SnapshotCompareRequest(envId, bundle, rules));
    }

    private SnapshotBundleDto readBundle(byte[] bytes, String fileName) {
        try {
            return objectMapper.readValue(bytes, SnapshotBundleDto.class);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("snapshot.unreadable", fileName == null ? "file" : fileName, e.getOriginalMessage());
        } catch (IOException e) {
            throw ApiException.badRequest("snapshot.unreadable", fileName == null ? "file" : fileName, e.getMessage());
        }
    }

    /** Applies and commits; with {@code dryRun: true} returns a dry-run report instead (nothing is stored). */
    @PostMapping("/apply")
    public Object apply(@RequestBody SnapshotApplyRequest request) {
        return request != null && request.dryRun() ? snapshots.dryRun(request) : snapshots.apply(request);
    }
}
