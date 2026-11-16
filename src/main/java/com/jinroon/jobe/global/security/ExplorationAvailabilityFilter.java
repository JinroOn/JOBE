package com.jinroon.jobe.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.global.exception.error.ErrorDto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Deployment switch only; enabled does not bypass authentication or catalog approval. */
public class ExplorationAvailabilityFilter extends OncePerRequestFilter {
    private final boolean enabled;
    private final ObjectMapper mapper;
    public ExplorationAvailabilityFilter(boolean enabled, ObjectMapper mapper) {
        this.enabled=enabled; this.mapper=mapper;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(path.startsWith("/api/exploration/v1/shared-results/") || path.matches("/api/exploration/v1/results/[^/]+/share(?:/revoke)?")){
            response.setHeader("Cache-Control","no-store, private");response.setHeader("Pragma","no-cache");
            response.setHeader("Referrer-Policy","no-referrer");response.setHeader("X-Robots-Tag","noindex, nofollow");
        }
        if (!enabled && (path.equals("/api/exploration/v1") || path.startsWith("/api/exploration/v1/"))) {
            response.setStatus(404);
            response.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(response.getOutputStream(), ErrorDto.of(ErrorCode.NOT_FOUND));
            return;
        }
        chain.doFilter(request,response);
    }
}
