package app.quorum.voting.web.dto;

import app.quorum.voting.domain.VoteValue;

public record CastVoteRequest(VoteValue value) {}
