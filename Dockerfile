FROM gradle:8-jdk17 AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN groupadd -r placy && useradd --no-log-init -r -g placy placy

COPY --from=build /app/build/libs/Placy.jar /app/placy.jar

RUN chown -R placy:placy /app

USER placy

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/placy.jar"]
