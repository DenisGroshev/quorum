package app.quorum.session.domain;

import java.util.List;

public record DeckResult(List<ScoredMovie> movies, String configVersion) {}
