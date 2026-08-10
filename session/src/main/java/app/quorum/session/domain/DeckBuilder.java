package app.quorum.session.domain;

public interface DeckBuilder {
    DeckResult build(DeckRequest request);
}
