package com.vodianoy.pgvc.migration;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** Offline SQL bundles for isolated environments. */
@RestController
@RequestMapping("/api/migrations")
@RequiredArgsConstructor
public class MigrationExportController {

    static final MediaType SQL = new MediaType("application", "sql", StandardCharsets.UTF_8);

    private final OfflineMigrationService offlineMigrations;

    /**
     * Downloads the bundle as a .sql file ({@code Content-Disposition: attachment}).
     *
     * @param from commit the target is at (exclusive); omit for a target with an empty history
     * @param to   last commit of the bundle (inclusive); omit for the HEAD of the source
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam("source") String sourceEnvId,
                                         @RequestParam(name = "from", required = false) String from,
                                         @RequestParam(name = "to", required = false) String to) {
        OfflineBundle bundle = offlineMigrations.generateBundle(sourceEnvId, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(bundle.fileName()).build().toString())
                .contentType(SQL)
                .body(bundle.sql().getBytes(StandardCharsets.UTF_8));
    }

    /** The same bundle as JSON (file name, commits, SQL) for a preview before downloading. */
    @GetMapping("/preview")
    public OfflineBundle preview(@RequestParam("source") String sourceEnvId,
                                 @RequestParam(name = "from", required = false) String from,
                                 @RequestParam(name = "to", required = false) String to) {
        return offlineMigrations.generateBundle(sourceEnvId, from, to);
    }
}
