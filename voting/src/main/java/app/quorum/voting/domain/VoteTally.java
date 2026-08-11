package app.quorum.voting.domain;

public record VoteTally(long tmdbId, int points, int superlikes, int dislikes) {}
