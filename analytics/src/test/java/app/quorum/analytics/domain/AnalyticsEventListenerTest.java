package app.quorum.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.quorum.common.analytics.AnalyticsEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalyticsEventListenerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private AnalyticsEventRepository repository;

    @Test
    void onAnalyticsEvent_savesEntityWithTypeUserIdPropsAndClockTime() {
        AnalyticsEventListener listener = new AnalyticsEventListener(repository, CLOCK);
        UUID userId = UUID.randomUUID();
        AnalyticsEvent event = new AnalyticsEvent("session_created", userId, Map.of("deckSize", 20));

        listener.onAnalyticsEvent(event);

        ArgumentCaptor<AnalyticsEventEntity> captor = ArgumentCaptor.forClass(AnalyticsEventEntity.class);
        verify(repository).save(captor.capture());
        AnalyticsEventEntity saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("session_created");
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getProps()).isEqualTo(Map.of("deckSize", 20));
        assertThat(saved.getOccurredAt()).isEqualTo(NOW);
    }

    @Test
    void onAnalyticsEvent_repositoryThrows_exceptionDoesNotPropagate() {
        AnalyticsEventListener listener = new AnalyticsEventListener(repository, CLOCK);
        when(repository.save(any())).thenThrow(new RuntimeException("disk full"));

        assertThatCode(() -> listener.onAnalyticsEvent(new AnalyticsEvent("rating_set", UUID.randomUUID())))
                .doesNotThrowAnyException();
    }
}
