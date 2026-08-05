package app.quorum.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import app.quorum.common.ContentLang;
import org.junit.jupiter.api.Test;

class SearchCacheKeyTest {

    @Test
    void sameQuery_differentCaseAndSpacing_produceEqualKeys() {
        SearchCacheKey a = SearchCacheKey.of("Interstellar", 1, ContentLang.RU);
        SearchCacheKey b = SearchCacheKey.of("  interstellar  ", 1, ContentLang.RU);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void sameQuery_differentLanguage_produceDifferentKeys() {
        SearchCacheKey ru = SearchCacheKey.of("interstellar", 1, ContentLang.RU);
        SearchCacheKey en = SearchCacheKey.of("interstellar", 1, ContentLang.EN);

        assertThat(ru).isNotEqualTo(en);
    }

    @Test
    void sameQuery_differentPage_produceDifferentKeys() {
        SearchCacheKey page1 = SearchCacheKey.of("interstellar", 1, ContentLang.RU);
        SearchCacheKey page2 = SearchCacheKey.of("interstellar", 2, ContentLang.RU);

        assertThat(page1).isNotEqualTo(page2);
    }

    @Test
    void nullQuery_normalizesToEmptyString_doesNotThrow() {
        SearchCacheKey key = SearchCacheKey.of(null, 1, ContentLang.RU);

        assertThat(key.normalizedQuery()).isEmpty();
    }
}
