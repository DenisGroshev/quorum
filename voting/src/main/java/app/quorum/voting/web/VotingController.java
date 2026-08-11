package app.quorum.voting.web;

import app.quorum.common.exception.SessionStateConflictException;
import app.quorum.common.web.RequestAttributes;
import app.quorum.session.api.SessionGuard;
import app.quorum.voting.domain.VotingQueryService;
import app.quorum.voting.domain.VotingService;
import app.quorum.voting.web.dto.CastVoteRequest;
import app.quorum.voting.web.dto.DeckResponse;
import app.quorum.voting.web.dto.ProgressResponse;
import app.quorum.voting.web.dto.ResultResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
public class VotingController {

    private final VotingService votingService;
    private final VotingQueryService votingQueryService;
    private final SessionGuard sessionGuard;

    public VotingController(
            VotingService votingService, VotingQueryService votingQueryService, SessionGuard sessionGuard) {
        this.votingService = votingService;
        this.votingQueryService = votingQueryService;
        this.sessionGuard = sessionGuard;
    }

    @GetMapping("/{id}/deck")
    public DeckResponse deck(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId, @PathVariable UUID id) {
        return votingQueryService.deck(id, userId);
    }

    @PutMapping("/{id}/votes/{tmdbId}")
    public ResponseEntity<Void> castVote(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID id,
            @PathVariable long tmdbId,
            @RequestBody CastVoteRequest request) {
        votingService.castVote(id, userId, tmdbId, request.value());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/progress")
    public ProgressResponse progress(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId, @PathVariable UUID id) {
        return votingQueryService.progress(id, userId);
    }

    @PostMapping("/{id}/finish")
    public ResponseEntity<Void> finish(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId, @PathVariable UUID id) {
        sessionGuard.requireHost(id, userId);
        boolean concluded = votingService.finishVoting(id);
        if (!concluded) {
            throw new SessionStateConflictException("session.illegal-transition");
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/result")
    public ResultResponse result(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId, @PathVariable UUID id) {
        return votingQueryService.result(id, userId);
    }
}
