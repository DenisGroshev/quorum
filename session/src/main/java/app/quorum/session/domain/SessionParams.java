package app.quorum.session.domain;

public record SessionParams(String title, Integer deckSize, Integer voteDeadlineMinutes, boolean watchlistOnly) {}
