package app.quorum.session.web;

import app.quorum.common.ContentLang;
import app.quorum.common.web.RequestAttributes;
import app.quorum.session.domain.SessionQueryService;
import app.quorum.session.domain.SessionService;
import app.quorum.session.infra.SessionViewRepository;
import app.quorum.session.web.dto.CreateSessionRequest;
import app.quorum.session.web.dto.JoinSessionRequest;
import app.quorum.session.web.dto.JoinSessionResponse;
import app.quorum.session.web.dto.RatingRequest;
import app.quorum.session.web.dto.SessionCreationResponse;
import app.quorum.session.web.dto.SessionListResponse;
import app.quorum.session.web.dto.SessionViewResponse;
import app.quorum.session.web.dto.UpdateSessionParamsRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final SessionViewRepository sessionViewRepository;
    private final SessionQueryService sessionQueryService;

    public SessionController(
            SessionService sessionService,
            SessionViewRepository sessionViewRepository,
            SessionQueryService sessionQueryService) {
        this.sessionService = sessionService;
        this.sessionViewRepository = sessionViewRepository;
        this.sessionQueryService = sessionQueryService;
    }

    @PostMapping
    public SessionCreationResponse create(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId,
            @RequestBody CreateSessionRequest request) {
        return SessionCreationResponse.from(sessionService.create(userId, request));
    }

    @PostMapping("/join")
    public JoinSessionResponse join(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId,
            @RequestBody JoinSessionRequest request) {
        return new JoinSessionResponse(sessionService.join(request.inviteCode(), userId));
    }

    @GetMapping
    public SessionListResponse list(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId,
            @RequestAttribute(RequestAttributes.CONTENT_LANG_ATTRIBUTE) ContentLang lang) {
        return new SessionListResponse(
                sessionQueryService.listActive(userId), sessionQueryService.listHistory(userId, lang));
    }

    @GetMapping("/{id}")
    public SessionViewResponse view(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId, @PathVariable UUID id) {
        return sessionViewRepository.viewFor(id, userId);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> updateParams(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID id,
            @RequestBody UpdateSessionParamsRequest request) {
        sessionService.updateParams(id, userId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Void> start(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId, @PathVariable UUID id) {
        sessionService.startVoting(id, userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId, @PathVariable UUID id) {
        sessionService.cancelSession(id, userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/watched")
    public ResponseEntity<Void> watched(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId, @PathVariable UUID id) {
        sessionService.markWatched(id, userId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<Void> kick(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID actorId,
            @PathVariable UUID id,
            @PathVariable("userId") UUID targetId) {
        sessionService.kick(id, actorId, targetId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/my-rating")
    public ResponseEntity<Void> rate(
            @RequestAttribute(RequestAttributes.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID id,
            @RequestBody RatingRequest request) {
        sessionService.rate(id, userId, request.value());
        return ResponseEntity.noContent().build();
    }
}
