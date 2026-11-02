package com.vodianoy.pgvc.matrix;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** N-database comparison matrix. */
@RestController
@RequestMapping("/api/vc/matrix")
@RequiredArgsConstructor
public class MatrixController {

    private final MatrixCompareService matrix;

    /** Environments and uploaded {@code .pgsnap.json} files compared with one baseline. */
    @PostMapping("/compare")
    public MatrixCompareResponse compare(@RequestBody MatrixCompareRequest request) {
        return matrix.compare(request);
    }
}
