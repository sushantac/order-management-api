package com.company.orderapi.security.pii;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PR #27 - registers {@link SensitiveDataSerializer} on every DTO property
 * carrying {@link MaskedPii}.
 *
 * <p>Spring Boot auto-discovers any {@code Module} bean and adds it to the
 * shared {@code ObjectMapper} (the one MockMvc and the controllers use), so a
 * single component wires the rule everywhere - no per-endpoint code needed.
 *
 * <p>The annotation is authoritative; a small name fallback (email/phone/fullName)
 * keeps serialization safe even for DTO fields that forget to annotate.
 */
@Component
public class PiiMaskingModule extends SimpleModule {

    public PiiMaskingModule() {
        super("pii-masking");
    }

    @Override
    public void setupModule(SetupContext context) {
        context.addBeanSerializerModifier(new BeanSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(
                    SerializationConfig config, BeanDescription beanDesc,
                    List<BeanPropertyWriter> beanProperties) {
                for (BeanPropertyWriter writer : beanProperties) {
                    PiiType type = typeOf(writer);
                    if (type != null) {
                        writer.assignSerializer(new SensitiveDataSerializer(type));
                    }
                }
                return beanProperties;
            }
        });
    }

    private PiiType typeOf(BeanPropertyWriter writer) {
        MaskedPii masked = writer.getAnnotation(MaskedPii.class);
        if (masked != null) {
            return masked.value();
        }
        String name = writer.getName();
        if (name.equals("email") || name.equals("customerEmail")) {
            return PiiType.EMAIL;
        }
        if (name.equals("fullName")) {
            return PiiType.NAME;
        }
        if (name.equals("phoneNumber")) {
            return PiiType.PHONE;
        }
        return null;
    }
}
