package com.vodianoy.pgvc.diff;

/** Kind of database object a {@link ChangeItem} is about. */
public enum ObjectType {
    SCHEMA,
    TABLE,
    PARTITION,
    COLUMN,
    CONSTRAINT,
    INDEX,
    VIEW,
    SEQUENCE
}
