package app.quorum.user.domain;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.common.ContentLang;
import app.quorum.common.exception.DomainRuleException;
import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    private UUID id;

    @Column(name = "tg_user_id", unique = true)
    private Long tgUserId;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(length = 64)
    private String username;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "lang_content", nullable = false, length = 2)
    private ContentLang langContent;

    @Column(name = "notify_enabled", nullable = false)
    private boolean notifyEnabled;

    @Column(name = "token_ver", nullable = false)
    private int tokenVer;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_fav_genres", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "genre_id")
    private Set<Integer> favGenreIds = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public static User registerFromTelegram(TelegramProfile p, Clock clock) {
        User u = new User();
        u.id = UuidCreator.getTimeOrderedEpoch();
        u.tgUserId = p.tgUserId();
        u.langContent = ContentLang.fromTelegramCode(p.languageCode());
        u.notifyEnabled = true;
        u.tokenVer = 0;
        u.createdAt = clock.instant();
        u.syncFromTelegram(p);
        return u;
    }

    public void syncFromTelegram(TelegramProfile p) {
        this.displayName = p.displayName();
        this.username = p.username();
        this.avatarUrl = p.avatarUrl();
    }

    public void replaceFavGenres(Set<Integer> genreIds) {
        if (genreIds.size() > 10) throw new DomainRuleException("user.genres-limit");
        favGenreIds.clear();
        favGenreIds.addAll(genreIds);
    }

    public void anonymize(Clock clock) {
        this.tgUserId = null;
        this.username = null;
        this.avatarUrl = null;
        this.displayName = "Удалённый пользователь";
        this.notifyEnabled = false;
        this.deletedAt = clock.instant();
        this.tokenVer++;
    }

    public void bumpTokenVersion() {
        this.tokenVer++;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return User.class.hashCode();
    }
}
