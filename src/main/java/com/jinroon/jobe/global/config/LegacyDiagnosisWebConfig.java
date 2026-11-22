package com.jinroon.jobe.global.config;

import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 기존 진단(회차·결과·주간 계획·상담) API 차단. 코드와 데이터는 남겨 두고 연결만 끊는다.
 * 컨트롤러와 같은 경로 해석을 쓰도록 필터가 아니라 인터셉터로 막는다.
 */
@Configuration
public class LegacyDiagnosisWebConfig implements WebMvcConfigurer {

    private static final String[] LEGACY_PATHS = {
            "/api/diagnoses/**", "/api/results/**", "/api/plans/**", "/api/consultations/**"
    };

    private final boolean legacyEnabled;

    public LegacyDiagnosisWebConfig(@Value("${diagnosis.legacy-enabled:false}") boolean legacyEnabled) {
        this.legacyEnabled = legacyEnabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (legacyEnabled) return;
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                throw new CustomException(ErrorCode.LEGACY_DIAGNOSIS_RETIRED);
            }
        }).addPathPatterns(LEGACY_PATHS);
    }
}
