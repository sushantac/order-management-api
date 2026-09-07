package com.company.orderapi.security.pii;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * PR #27 - the Jackson serializer that guards personal data at the API edge.
 *
 * <p>{@link PiiMaskingModule} assigns one instance of this serializer to every
 * DTO property annotated {@link MaskedPii}, carrying the property's
 * {@link PiiType}. At write time it consults {@link PiiAccessDecider} so the
 * raw value is only emitted for principals allowed to see it - everyone else
 * receives the masked form, without the controller knowing anything about it.
 *
 * <p>Extends {@code JsonSerializer<Object>} because Jackson's property-writer
 * API types value serializers that way; at runtime every value here is the
 * annotated String property (or null).
 */
public class SensitiveDataSerializer extends JsonSerializer<Object> {

    private final PiiType type;

    /** Jackson may instantiate this class reflectively; NAME is a safe default. */
    public SensitiveDataSerializer() {
        this(PiiType.NAME);
    }

    public SensitiveDataSerializer(PiiType type) {
        this.type = type;
    }

    @Override
    public void serialize(Object value, JsonGenerator gen,
                          SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        String text = value instanceof String s ? s : String.valueOf(value);
        String out = PiiAccessDecider.shouldMask() ? PiiMasker.mask(text, type) : text;
        gen.writeString(out);
    }
}
