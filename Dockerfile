FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
ARG SERVICE
COPY . .
RUN mvn -B -pl "$SERVICE" -am package -DskipTests && cp "$SERVICE"/target/"$SERVICE"-1.0.0.jar /service.jar
FROM eclipse-temurin:21.0.9_10-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd -r diamond && useradd -r -g diamond diamond
USER diamond
COPY --from=build /service.jar /service.jar
ENTRYPOINT ["java","-jar","/service.jar"]
