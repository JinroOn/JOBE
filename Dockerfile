# =============================================
# Spring Boot 실행 이미지
# =============================================
# JAR는 GitHub Actions(deploy.yml)에서 ./gradlew bootJar로 미리 만든다.
# build.gradle에서 plain jar를 끄므로 build/libs에는 실행 가능한 JAR 하나만 생긴다.

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY build/libs/*.jar app.jar

# 파일 기반 전공/RAG/탐색형 카탈로그 데이터셋
COPY datasets ./datasets

RUN mkdir -p /app/logs

EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
