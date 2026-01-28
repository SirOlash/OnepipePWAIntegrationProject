# Stage 1: Build using an official Maven image
# This image has 'mvn' installed, so we don't need 'mvnw'
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy only the POM first to cache dependencies
COPY pom.xml .
# Download dependencies (this step will be cached if pom.xml doesn't change)
RUN mvn dependency:go-offline -B

# Copy the source code
COPY src ./src

# Build the app (Skip tests to speed up deployment and avoid environment issues)
RUN mvn clean package -DskipTests

# Stage 2: Run using a slim JRE image
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the JAR from the build stage
# Note: The JAR is usually in target/onepipe-boarding-school-1.0-SNAPSHOT.jar
# We use a wildcard *.jar to be safe
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
