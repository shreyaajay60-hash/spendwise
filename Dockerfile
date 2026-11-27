# Multi-stage Dockerfile for SpendWise All-in-One Cloud Deployment
# Combines Java 21 Spring Boot + Relational JPA + React 18 Web UI

FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source and bundled React frontend in static/
COPY src ./src
RUN mvn clean package -DskipTests

# Lightweight production runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create persistent data directory for H2/disk database
RUN mkdir -p /app/data && chmod 777 /app/data
VOLUME /app/data

# Copy built unified JAR
COPY --from=build /app/target/spendwise-backend-*.jar app.jar

# Render / Railway dynamic port configuration
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-Dserver.port=${PORT}", "-jar", "app.jar"]
