package app.quorum.web;

import app.quorum.user.service.AccessTokenAuthenticationService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AccessTokenAuthenticationService authenticationService;

    public WebConfig(AccessTokenAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BearerAuthInterceptor(authenticationService))
                .addPathPatterns("/api/v1/users/**");
    }
}
