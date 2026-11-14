package com.jinroon.jobe.global.security;

import com.jinroon.jobe.global.exception.CustomException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final CustomUserDetailsService customUserDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (token != null && jwtProvider.isValid(token)) {
            Claims claims = jwtProvider.getClaims(token);
            CustomUserDetails userDetails = loadActiveUser(claims);
            if (userDetails != null) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        // 인증 정보를 넣지 않고 넘기면 보호 경로는 SecurityConfig의 entry point가 401로 응답한다.
        filterChain.doFilter(request, response);
    }

    /**
     * 토큰의 사용자가 지금도 존재하고 활성 상태일 때만 반환한다.
     * 삭제된 사용자, 정지·탈퇴 사용자, 같은 이메일로 다시 가입한 다른 계정(userId 불일치)은 null.
     */
    private CustomUserDetails loadActiveUser(Claims claims) {
        CustomUserDetails userDetails;
        try {
            userDetails = (CustomUserDetails) customUserDetailsService.loadUserByUsername(claims.getSubject());
        } catch (CustomException exception) {
            return null;
        }
        if (!userDetails.isEnabled()) {
            return null;
        }
        Long tokenUserId = claims.get("userId", Long.class);
        if (tokenUserId == null || !tokenUserId.equals(userDetails.getUserId())) {
            return null;
        }
        return userDetails;
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
