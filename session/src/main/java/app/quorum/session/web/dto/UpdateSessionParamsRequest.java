package app.quorum.session.web.dto;

public record UpdateSessionParamsRequest(
        String title, Integer deckSize, Integer voteDeadlineMinutes, Boolean watchlistOnly) {}
