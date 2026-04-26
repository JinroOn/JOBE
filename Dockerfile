# =============================================
# Spring Boot Dockerfile
# =============================================

# 1단계: 빌드 (선택사항 - GitHub Actions에서 이미 빌드하므로 생략 가능)
# FROM gradle:8.5-jdk17 AS build
# WORKDIR /app
# COPY . .
# RUN gradle bootJar -x test

# 2단계: 실행 이미지 (경량 JRE)
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 빌드된 jar 복사 (경로는 프로젝트에 맞게 수정)
COPY build/libs/*.jar app.jar

# 로그 디렉토리 생성
RUN mkdir -p /app/logs

# 포트 노출
EXPOSE 8080

# 실행
ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", \
  "app.jar"]
