# ---- build stage: compile the Spring Boot fat jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- run stage: slim JRE with just the jar ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Hugging Face Spaces route to the port declared as app_port in README.md.
ENV PORT=7860
EXPOSE 7860

# Memory tuned for 512 MB free tier (SerialGC, reduced stacks, 200MB max heap)
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xss256k", "-Xmx200m", "-XX:MaxDirectMemorySize=32m", "-XX:MaxMetaspaceSize=64m", "-jar", "app.jar"]
