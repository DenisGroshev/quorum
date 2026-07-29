package app.quorum.matching.api;

import app.quorum.session.domain.DeckResult;

public interface DeckBuilder {
    DeckResult build(DeckRequest request);
}
