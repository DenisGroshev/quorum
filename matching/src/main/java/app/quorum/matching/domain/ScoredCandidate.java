package app.quorum.matching.domain;

import java.math.BigDecimal;

public record ScoredCandidate(Candidate candidate, BigDecimal score) {}
