# Build and test the executable JAR with Java 21 before building this image.
# This image packages that exact artifact; it does not compile source code.
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY --chown=10001:10001 build/libs/together-market-0.0.1-SNAPSHOT.jar /app/app.jar

# Runtime secrets and profiles are supplied by the deployment environment.
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
