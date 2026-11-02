package com.vodianoy.pgvc.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import com.vodianoy.pgvc.model.schema.SequenceModel;
import com.vodianoy.pgvc.support.TestDatabases;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A .pgsnap.json that went through a browser (JSON.parse + JSON.stringify) must still be readable: JavaScript turns
 * 9223372036854775807 (MAXVALUE of a bigint sequence) into 9223372036854776000.
 */
class SnapshotJsonRoundTripTest {

    /** Strict mapper (FAIL_ON_UNKNOWN_PROPERTIES on): the models themselves must be tolerant. */
    private final ObjectMapper json = TestDatabases.objectMapper();

    @Test
    void bigintSequenceLimitsRoundedByJavaScriptAreClamped() throws Exception {
        String file = """
                {"formatVersion":"1.0","exportedAt":"2026-11-02T10:00:00Z","sourceEnvironmentName":"Dev",
                 "sourceBranch":"dev","sourceCommitHash":null,"sourceCommitMessage":null,"someFutureField":{"x":1},
                 "schemaSnapshot":{"formatVersion":2,"timestamp":"2026-11-02T10:00:00Z","sourceDatabaseId":"1:2@dev",
                   "serverVersion":160004,"schemas":["public"],"futureList":[1,2],
                   "tables":{},"views":{},"functions":{},"enumTypes":{},"extensions":{},
                   "sequences":{"public.docs_id_seq":{"oid":16400,"schemaName":"public","name":"docs_id_seq",
                     "dataType":"bigint","startValue":1,"incrementBy":1,"minValue":1,
                     "maxValue":9223372036854776000,"cacheSize":1,"cycle":false,"futureFlag":true},
                     "public.down_seq":{"oid":16401,"schemaName":"public","name":"down_seq","dataType":"bigint",
                     "startValue":"-1","incrementBy":-1.0,"minValue":-9223372036854776000,"maxValue":-1,
                     "cacheSize":1,"cycle":false}}}}
                """;

        SnapshotBundleDto bundle = json.readValue(file, SnapshotBundleDto.class);

        SequenceModel up = bundle.getSchemaSnapshot().getSequences().get("public.docs_id_seq");
        assertThat(up.getMaxValue()).isEqualTo(Long.MAX_VALUE);
        SequenceModel down = bundle.getSchemaSnapshot().getSequences().get("public.down_seq");
        assertThat(down.getMinValue()).isEqualTo(Long.MIN_VALUE);
        assertThat(down.getStartValue()).isEqualTo(-1);
        assertThat(down.getIncrementBy()).isEqualTo(-1);
    }

    @Test
    void exportedBytesReadBackUnchanged() throws Exception {
        DatabaseSnapshot snapshot = DatabaseSnapshot.builder().sourceDatabaseId("1:2@dev").serverVersion(160004)
                .schemas(List.of("public")).build();
        snapshot.getSequences().put("public.s", SequenceModel.builder().oid(1).schemaName("public").name("s")
                .dataType("bigint").startValue(1).incrementBy(1).minValue(1).maxValue(Long.MAX_VALUE).cacheSize(1).build());
        SnapshotBundleDto bundle = SnapshotBundleDto.builder().sourceEnvironmentName("Dev").schemaSnapshot(snapshot).build();

        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle);
        SnapshotBundleDto back = json.readValue(bytes, SnapshotBundleDto.class);

        assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).startsWith("{").contains("\"maxValue\" : 9223372036854775807");
        assertThat(back.getSchemaSnapshot().getSequences().get("public.s").getMaxValue()).isEqualTo(Long.MAX_VALUE);
    }
}
