package app.quorum.session.domain;

import java.io.Serializable;
import java.util.Map;

public record DeckReason(String code, Map<String, Object> args) implements Serializable {}
