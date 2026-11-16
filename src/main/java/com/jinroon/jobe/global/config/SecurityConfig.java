package com.jinroon.jobe.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.global.exception.error.ErrorDto;
import com.jinroon.jobe.global.security.CustomUserDetailsService;
import com.jinroon.jobe.global.security.JwtFilter;
import com.jinroon.jobe.global.security.JwtProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final CustomUserDetailsService customUserDetailsService;
    private final ObjectMapper objectMapper;

    /**
     * 브라우저에서 API를 호출할 수 있는 출처(프론트 주소) 목록. 쉼표로 구분한다.
     * 운영에서는 환경변수 CORS_ALLOWED_ORIGINS로 배포 주소만 넣는다.
     * 와일드카드 패턴(예: https://jinro-on-*.vercel.app)도 쓸 수 있다.
     */
    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Value("${exploration.enabled:false}")
    private boolean explorationEnabled;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasRole("admin")
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/signup",
                                "/api/auth/login",
                                "/api/auth/refresh",
                                // refresh 토큰 자체로 세션을 지운다. access 토큰이 만료된 뒤에도 로그아웃이 되어야 한다.
                                "/api/auth/logout",
                                "/api/auth/email-verifications",
                                "/api/auth/email-verifications/confirm",
                                "/api/auth/password-resets",
                                "/api/auth/password-resets/confirm"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/results/share/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/exploration/v1/shared-results/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/majors", "/api/majors/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/notices", "/api/notices/**").permitAll()
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/actuator/health"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/users").hasRole("admin")
                        .requestMatchers(HttpMethod.POST, "/api/users").hasRole("admin")
                        .requestMatchers(HttpMethod.POST, "/api/users/email-verifications").hasRole("admin")
                        .requestMatchers(HttpMethod.POST, "/api/majors").hasRole("admin")
                        .requestMatchers(HttpMethod.PATCH, "/api/majors/**").hasRole("admin")
                        .requestMatchers(HttpMethod.DELETE, "/api/majors/**").hasRole("admin")
                        .requestMatchers(HttpMethod.POST, "/api/diagnoses/questions").hasRole("admin")
                        .requestMatchers(HttpMethod.POST, "/api/notices").hasRole("admin")
                        .requestMatchers(HttpMethod.PATCH, "/api/notices/**").hasRole("admin")
                        .requestMatchers(HttpMethod.DELETE, "/api/notices/**").hasRole("admin")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(new JwtFilter(jwtProvider, customUserDetailsService), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new com.jinroon.jobe.global.security.ExplorationAvailabilityFilter(explorationEnabled, objectMapper), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(401);
                            response.getWriter().write(
                                    objectMapper.writeValueAsString(ErrorDto.of(ErrorCode.UNAUTHORIZED))
                            );
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(403);
                            response.getWriter().write(
                                    objectMapper.writeValueAsString(ErrorDto.of(ErrorCode.FORBIDDEN))
                            );
                        })
                )
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 이전에는 모든 출처(*)와 credentials를 함께 허용해 어느 사이트에서든 API를 호출할 수 있었다.
        config.setAllowedOriginPatterns(allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key"));
        // 프론트는 쿠키 없이 Authorization 헤더로 인증하므로 credentials는 허용하지 않는다.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
