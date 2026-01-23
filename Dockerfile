# Java 21 환경 사용
FROM eclipse-temurin:21-jdk
# 빌드된 jar 파일을 app.jar라는 이름으로 복사
COPY build/libs/*.jar app.jar
# 서버 실행 시 prod와 loadtest 프로필 활성화
ENTRYPOINT ["java", "-Dspring.profiles.active=prod,loadtest", "-jar", "/app.jar"]