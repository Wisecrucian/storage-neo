# Build stage
FROM gradle:8.5-jdk17 AS build
WORKDIR /app

# Copy gradle files
COPY build.gradle settings.gradle ./
COPY gradle ./gradle

# Copy all modules
COPY event-storage-server ./event-storage-server
COPY event-storage-client ./event-storage-client

# Build the server module
RUN gradle :event-storage-server:bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built jar from server module
COPY --from=build /app/event-storage-server/build/libs/*.jar app.jar

# Expose port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]

