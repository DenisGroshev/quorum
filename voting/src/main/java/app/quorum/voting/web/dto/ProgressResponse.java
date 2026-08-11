package app.quorum.voting.web.dto;

public record ProgressResponse(int totalMembers, int finishedMembers, int cardsRemainingForMe) {}
