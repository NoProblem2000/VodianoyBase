package com.vodianoy.pgvc.model.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A relation (and optionally one of its columns) a view reads. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ViewDependency {

    /** Referenced relation as {@code schema.name}. */
    private String relation;

    /** pg_class.relkind of the referenced relation: r, p, v, m, f ... */
    private String relationKind;

    /** Referenced column, or null when the view depends on the relation as a whole. */
    private String column;
}
