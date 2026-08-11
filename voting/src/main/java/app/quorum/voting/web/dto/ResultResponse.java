package app.quorum.voting.web.dto;

import java.util.List;

public record ResultResponse(
        MovieSummary winner, boolean contested, List<TopEntry> top3, List<SuperlikeEntry> superlikes) {}
