package com.vodianoy.pgvc.refdata;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** "📌 Reference Data Tables" of an environment. */
@RestController
@RequestMapping("/api/environments/{envId}/reference-data")
@RequiredArgsConstructor
public class ReferenceDataController {

    private final ReferenceDataService referenceData;

    @GetMapping
    public List<ReferenceDataTableView> list(@PathVariable String envId) {
        return referenceData.list(envId);
    }

    @PostMapping
    public ReferenceDataTableView track(@PathVariable String envId, @RequestBody TrackTableRequest request) {
        return referenceData.track(envId, request);
    }

    /** {@code ?table=schema.table} (a query parameter: table names may contain characters that are awkward in paths). */
    @DeleteMapping
    public ResponseEntity<Void> untrack(@PathVariable String envId, @RequestParam String table) {
        referenceData.untrack(envId, table);
        return ResponseEntity.noContent().build();
    }
}
