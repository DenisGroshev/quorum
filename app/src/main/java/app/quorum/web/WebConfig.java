package app.quorum.web;

import app.quorum.user.repository.UserRepository;
import app.quorum.user.service.AccessTokenAuthenticationService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AccessTokenAuthenticationService authenticationService;
    private final UserRepository userRepository;

    public WebConfig(AccessTokenAuthenticationService authenticationService, UserRepository userRepository) {
        this.authenticationService = authenticationService;
        this.userRepository = userRepository;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // /api/v1/meta/** (атрибуция TMDB) намеренно не в списке — доступен без токена.
        registry.addInterceptor(new BearerAuthInterceptor(authenticationService, userRepository))
                .addPathPatterns("/api/v1/users/**", "/api/v1/movies/**", "/api/v1/genres/**");
    }
}
