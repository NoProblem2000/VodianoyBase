package com.vodianoy.pgvc.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlTest {

    @Test
    void identQuotesLikeQuoteIdent() {
        assertEquals("users", Sql.ident("users"));
        assertEquals("\"Users\"", Sql.ident("Users"));
        assertEquals("\"user\"", Sql.ident("user"));
        assertEquals("\"a\"\"b\"", Sql.ident("a\"b"));
        assertEquals("\"1abc\"", Sql.ident("1abc"));
    }

    @Test
    void literalEscapesQuotes() {
        assertEquals("'it''s'", Sql.literal("it's"));
        assertEquals("NULL", Sql.literal(null));
    }

    @Test
    void dollarQuoteAvoidsCollisions() {
        String q = Sql.dollar("body with $pgvc$ inside");
        assertTrue(q.startsWith("$pgvc1$"), q);
        assertFalse(q.substring(7, q.length() - 7).contains("$pgvc1$"));
    }
}
