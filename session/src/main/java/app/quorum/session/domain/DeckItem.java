package app.quorum.session.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "deck_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeckItem {

    @EmbeddedId
    private DeckItemKey key;

    @MapsId("sessionId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private Session session;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<DeckReason> reasons;

    DeckItem(Session s, Long tmdbId, int position, BigDecimal score, List<DeckReason> reasons) {
        this.key = new DeckItemKey(s.getId(), tmdbId);
        this.session = s;
        this.position = position;
        this.score = score;
        this.reasons = reasons;
    }
}
