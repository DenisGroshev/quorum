package app.quorum.session.domain;

public record SessionParams(String title, short deckSize, int voteDeadlineMinutes, boolean watchlistOnly) {}
