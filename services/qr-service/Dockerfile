# 로컬에서 빌드한 JAR를 복사해서 실행
FROM eclipse-temurin:17-jre

WORKDIR /app

# 로컬에서 빌드한 JAR 복사
COPY build/libs/*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
