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
    SEQUENCE,
    FUNCTION,
    PROCEDURE,
    TRIGGER,
    /** ENUM type. */
    TYPE,
    EXTENSION,
    /** COMMENT ON a table, column, view, function or type (the commented object is the item's root). */
    COMMENT
}
