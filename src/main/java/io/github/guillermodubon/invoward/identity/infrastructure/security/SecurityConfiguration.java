package io.github.guillermodubon.invoward.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(
            CsrfTokenRepository csrfTokenRepository,
            SessionRegistry sessionRegistry) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository),
                new RegisterSessionAuthenticationStrategy(sessionRegistry)));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationConfiguration authenticationConfiguration,
            DaoAuthenticationProvider authenticationProvider,
            ObjectMapper objectMapper,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            SessionRegistry sessionRegistry,
            SessionInformationExpiredStrategy sessionInformationExpiredStrategy,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            JsonAuthenticationSuccessHandler successHandler,
            JsonAuthenticationFailureHandler failureHandler,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler) throws Exception {
        AuthenticationManager authenticationManager = authenticationConfiguration.getAuthenticationManager();
        JsonUsernamePasswordAuthenticationFilter loginFilter = new JsonUsernamePasswordAuthenticationFilter(
                authenticationManager, objectMapper, successHandler, failureHandler);
        loginFilter.setSecurityContextRepository(securityContextRepository);
        loginFilter.setSessionAuthenticationStrategy(sessionAuthenticationStrategy);

        http
                .authenticationProvider(authenticationProvider)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionAuthenticationStrategy(sessionAuthenticationStrategy))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                            @Override
                            public CsrfFilter postProcess(CsrfFilter filter) {
                                filter.setAccessDeniedHandler(accessDeniedHandler);
                                return filter;
                            }
                        }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info", "/api/auth/csrf")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/verify-email",
                                "/api/auth/resend-verification",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me")
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/analyses")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/analyses/*", "/api/analyses/*/status")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .rememberMe(AbstractHttpConfigurer::disable)
                .requestCache(requestCache -> requestCache.disable())
                .logout(logout -> logout
                        .logoutRequestMatcher(authenticatedLogoutRequestMatcher())
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("INVOWARD_SESSION")
                        .logoutSuccessHandler(logoutSuccessHandler()));

        http.addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAt(
                new ConcurrentSessionFilter(sessionRegistry, sessionInformationExpiredStrategy),
                ConcurrentSessionFilter.class);
        return http.build();
    }

    private static RequestMatcher authenticatedLogoutRequestMatcher() {
        return request -> "POST".equals(request.getMethod())
                && ("/api/auth/logout".equals(request.getServletPath())
                        || (request.getContextPath() + "/api/auth/logout").equals(request.getRequestURI()))
                && isAuthenticated(SecurityContextHolder.getContext().getAuthentication());
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static LogoutSuccessHandler logoutSuccessHandler() {
        return (request, response, authentication) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
