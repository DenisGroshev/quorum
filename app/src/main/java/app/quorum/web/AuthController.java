package app.quorum.web;

import app.quorum.common.exception.InvalidDataInitException;
import app.quorum.user.api.TelegramLoginResult;
import app.quorum.user.service.TelegramLoginService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String SCHEME_PREFIX = "tma ";

    private final TelegramLoginService loginService;

    public AuthController(TelegramLoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping("/telegram")
    public TelegramLoginResult telegram(@RequestHeader("Authorization") String authorization) {
        if (!authorization.startsWith(SCHEME_PREFIX)) {
            throw new InvalidDataInitException("Authorization header must use the 'tma' scheme");
        }
        String rawInitData = authorization.substring(SCHEME_PREFIX.length());
        return loginService.login(rawInitData);
    }
}
