package com.vodianoy.pgvc.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * {@code long} that survives a trip through JavaScript. A browser reads 9223372036854775807 (the MAXVALUE of every
 * bigint sequence) as the double 9223372036854775808 and writes it back as {@code 9223372036854776000}, which is out
 * of the range of {@code long}. Values beyond the range are clamped to {@link Long#MAX_VALUE} / {@link Long#MIN_VALUE}
 * (exactly what they were before the rounding); floats and numeric strings are accepted too.
 */
public class SaturatingLongDeserializer extends JsonDeserializer<Long> {

    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        JsonToken token = p.currentToken();
        BigInteger value;
        if (token == JsonToken.VALUE_NUMBER_INT) {
            value = p.getBigIntegerValue();
        } else if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            value = p.getDecimalValue().toBigInteger();
        } else if (token == JsonToken.VALUE_STRING) {
            String text = p.getText().strip();
            if (text.isEmpty()) {
                return 0L;
            }
            try {
                value = new BigDecimal(text).toBigInteger();
            } catch (NumberFormatException e) {
                return (Long) ctx.handleWeirdStringValue(Long.class, text, "not a number");
            }
        } else if (token == JsonToken.VALUE_NULL) {
            return 0L;
        } else {
            return (Long) ctx.handleUnexpectedToken(Long.class, p);
        }
        if (value.compareTo(MAX) > 0) {
            return Long.MAX_VALUE;
        }
        if (value.compareTo(MIN) < 0) {
            return Long.MIN_VALUE;
        }
        return value.longValue();
    }

    @Override
    public Long getNullValue(DeserializationContext ctx) {
        return 0L;
    }
}
