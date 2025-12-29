# Stage 1: build
FROM eclipse-temurin:17-jdk as build
WORKDIR /app
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
# cache maven dependencies
RUN mvn -B -f pom.xml dependency:go-offline

COPY src ./src
RUN mvn -B -f pom.xml clean package -DskipTests

# Stage 2: run
FROM eclipse-temurin:17-jre
ARG JAR_FILE=target/*.jar
WORKDIR /app
COPY --from=build /app/${JAR_FILE} app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
