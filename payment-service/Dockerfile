# Stage 1: Build Application
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Copy dependency definition for caching layer
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build executable jar
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Production JRE Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy executable jar from builder stage
COPY --from=builder /app/target/*.jar app.jar

# Switch to non-root user
USER appuser:appgroup

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]
