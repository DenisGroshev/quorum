package app.quorum.catalog.config;

import app.quorum.common.ContentLang;
import java.util.Locale;

/**
 * Ключ кэша поиска: нормализованный запрос + страница + язык.
 *
 * <p>Язык обязателен в ключе — без него первый же поисковый запрос одного
 * языка попадёт в кэш и будет отдан пользователям другого языка.
 */
public record SearchCacheKey(String normalizedQuery, int page, ContentLang lang) {

    public static SearchCacheKey of(String query, int page, ContentLang lang) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return new SearchCacheKey(normalized, page, lang);
    }
}
