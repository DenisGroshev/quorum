package app.quorum.session.web.dto;

import app.quorum.session.domain.SessionCreationResult;
import java.util.UUID;

public record SessionCreationResponse(UUID id, String inviteCode, String inviteUrl) {
    public static SessionCreationResponse from(SessionCreationResult result) {
        return new SessionCreationResponse(
                result.session().getId(), result.session().getInviteCode(), result.inviteLink());
    }
}
