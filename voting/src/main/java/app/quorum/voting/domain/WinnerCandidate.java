package app.quorum.voting.domain;

import java.math.BigDecimal;

public record WinnerCandidate(VoteTally tally, BigDecimal matchScore, Integer runtimeMin) {}
