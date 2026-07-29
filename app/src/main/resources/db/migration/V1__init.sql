CREATE TABLE users (
    id             UUID         PRIMARY KEY,
    tg_user_id     BIGINT       UNIQUE,
    display_name   VARCHAR(128) NOT NULL,
    username       VARCHAR(64),
    avatar_url     VARCHAR(512),
    lang_content   VARCHAR(2)   NOT NULL DEFAULT 'ru' CHECK (lang_content IN ('ru','en')),
    notify_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    token_ver      INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL,
    deleted_at     TIMESTAMPTZ
);

CREATE TABLE genres (
    id      INT         PRIMARY KEY,
    name_ru VARCHAR(64) NOT NULL,
    name_en VARCHAR(64) NOT NULL
);

CREATE TABLE user_fav_genres (
    user_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    genre_id INT  NOT NULL REFERENCES genres(id),
    PRIMARY KEY (user_id, genre_id)
);

CREATE TABLE movies (
    tmdb_id        BIGINT       PRIMARY KEY,
    title_ru       VARCHAR(512),
    title_en       VARCHAR(512),
    original_title VARCHAR(512) NOT NULL,
    release_year   SMALLINT,
    runtime_min    SMALLINT,
    poster_path    VARCHAR(256),
    vote_avg       NUMERIC(3,1) NOT NULL DEFAULT 0,
    vote_count     INT          NOT NULL DEFAULT 0,
    original_lang  VARCHAR(8),
    overview_ru    TEXT,
    overview_en    TEXT,
    synced_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_movies_synced ON movies(synced_at);

CREATE TABLE movie_genres (
    tmdb_id    BIGINT  NOT NULL REFERENCES movies(tmdb_id) ON DELETE CASCADE,
    genre_id   INT     NOT NULL REFERENCES genres(id),
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tmdb_id, genre_id)
);

CREATE TABLE watchlist_items (
    user_id  UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tmdb_id  BIGINT      NOT NULL REFERENCES movies(tmdb_id),
    added_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, tmdb_id)
);
CREATE INDEX idx_wl_user_added ON watchlist_items(user_id, added_at DESC);

CREATE TABLE seen_items (
    user_id   UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tmdb_id   BIGINT      NOT NULL REFERENCES movies(tmdb_id),
    marked_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, tmdb_id)
);

CREATE TABLE sessions (
    id                    UUID         PRIMARY KEY,
    host_id               UUID         NOT NULL REFERENCES users(id),
    title                 VARCHAR(128) NOT NULL,
    state                 VARCHAR(16)  NOT NULL CHECK (state IN ('LOBBY','VOTING','DECIDED','WATCHED','CANCELLED','EXPIRED','ARCHIVED')),
    invite_code           VARCHAR(10)  NOT NULL UNIQUE,
    deck_size             SMALLINT     NOT NULL CHECK (deck_size IN (10,20,30)),
    vote_deadline_minutes INT          NOT NULL,
    watchlist_only        BOOLEAN      NOT NULL DEFAULT FALSE,
    match_config_ver      VARCHAR(32),
    voting_started_at     TIMESTAMPTZ,
    voting_deadline_at    TIMESTAMPTZ,
    decided_at            TIMESTAMPTZ,
    watched_at            TIMESTAMPTZ,
    winner_tmdb_id        BIGINT REFERENCES movies(tmdb_id),
    winner_contested      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_sessions_state_created  ON sessions(state, created_at);
CREATE INDEX idx_sessions_state_deadline ON sessions(state, voting_deadline_at);

CREATE TABLE session_members (
    session_id           UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    user_id              UUID        NOT NULL REFERENCES users(id),
    is_host              BOOLEAN     NOT NULL DEFAULT FALSE,
    joined_at            TIMESTAMPTZ NOT NULL,
    completed_voting_at  TIMESTAMPTZ,
    rating               SMALLINT CHECK (rating BETWEEN 1 AND 5),
    PRIMARY KEY (session_id, user_id)
);
CREATE INDEX idx_members_user ON session_members(user_id);

CREATE TABLE deck_items (
    session_id UUID        NOT NULL,
    tmdb_id    BIGINT       NOT NULL REFERENCES movies(tmdb_id),
    position   INT          NOT NULL,
    score      NUMERIC(6,2) NOT NULL,
    reasons    JSONB        NOT NULL DEFAULT '[]',
    PRIMARY KEY (session_id, tmdb_id),
    UNIQUE (session_id, position),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

CREATE TABLE votes (
    session_id UUID        NOT NULL,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tmdb_id    BIGINT      NOT NULL,
    value      VARCHAR(10) NOT NULL CHECK (value IN ('LIKE','DISLIKE','SUPERLIKE')),
    voted_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_id, user_id, tmdb_id),
    FOREIGN KEY (session_id, tmdb_id) REFERENCES deck_items(session_id, tmdb_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uq_votes_one_superlike ON votes(session_id, user_id) WHERE value = 'SUPERLIKE';

CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
    replaced_by UUID,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_rt_user    ON refresh_tokens(user_id);
CREATE INDEX idx_rt_expires ON refresh_tokens(expires_at);

CREATE TABLE notification_outbox (
    id                UUID         PRIMARY KEY,
    event_key         VARCHAR(128) NOT NULL UNIQUE,
    recipient_user_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    template          VARCHAR(64)  NOT NULL,
    params            JSONB        NOT NULL DEFAULT '{}',
    status            VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED','SKIPPED')),
    attempts          INT          NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    sent_at           TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON notification_outbox(status, next_attempt_at);

CREATE TABLE analytics_events (
    id          UUID        PRIMARY KEY,
    user_id     UUID,
    type        VARCHAR(64) NOT NULL,
    props       JSONB       NOT NULL DEFAULT '{}',
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_analytics_type_time ON analytics_events(type, occurred_at);
