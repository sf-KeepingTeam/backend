# ==========================================
# Keeping Backend Dockerfile
# ==========================================
# 사용법:
# 1. 빌드: ./gradlew clean bootJar -x test
# 2. 이미지 생성: docker build -t username/keeping-backend:latest .
# 3. 실행: docker-compose up -d
# ==========================================

# Java 21 JRE 사용 (JDK보다 이미지 크기 작음)
FROM eclipse-temurin:21-jre

# 작업 디렉토리 설정
WORKDIR /app

# JAR 파일 복사
COPY build/libs/*.jar app.jar

# 환경변수 기본값
ENV SPRING_PROFILES_ACTIVE=prod,loadtest
ENV JAVA_OPTS="-Xms512m -Xmx1536m"
ENV TZ=Asia/Seoul

# 포트 노출
EXPOSE 8080

# 헬스체크
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# 애플리케이션 실행
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.profiles.active=$SPRING_PROFILES_ACTIVE -jar /app/app.jar"]