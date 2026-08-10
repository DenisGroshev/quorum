package app.quorum.session.web.dto;

public record CreateSessionRequest(
        String title, Integer deckSize, Integer voteDeadlineMinutes, boolean watchlistOnly) {}
