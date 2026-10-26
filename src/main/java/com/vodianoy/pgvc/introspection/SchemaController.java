package com.vodianoy.pgvc.introspection;

import com.vodianoy.pgvc.model.schema.DatabaseSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Live schema of an environment read from pg_catalog ("Working Tree"). */
@RestController
@RequestMapping("/api/environments/{envId}/schema")
@RequiredArgsConstructor
public class SchemaController {

    private final PostgresSchemaIntrospector introspector;

    /** Version-controllable user schemas of the database. */
    @GetMapping("/schemas")
    public List<String> schemas(@PathVariable String envId) {
        return introspector.listSchemas(envId);
    }

    /** Full snapshot; {@code ?schemas=sales,billing} restricts it to some schemas. */
    @GetMapping("/snapshot")
    public DatabaseSnapshot snapshot(@PathVariable String envId,
                                     @RequestParam(name = "schemas", required = false) List<String> schemas) {
        return introspector.introspect(envId, schemas);
    }
}
