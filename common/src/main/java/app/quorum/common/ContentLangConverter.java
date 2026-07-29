package app.quorum.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ContentLangConverter implements AttributeConverter<ContentLang, String> {
    @Override
    public String convertToDatabaseColumn(ContentLang lang) {
        return lang == null ? null : lang.name().toLowerCase();
    }

    @Override
    public ContentLang convertToEntityAttribute(String s) {
        return s == null ? null : ContentLang.valueOf(s.toUpperCase());
    }
}
