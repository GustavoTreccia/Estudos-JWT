FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system auth && useradd --system --gid auth auth
COPY --from=build /workspace/target/auth-service-0.0.1-SNAPSHOT.jar app.jar
USER auth
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
