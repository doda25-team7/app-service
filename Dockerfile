FROM eclipse-temurin:25-jdk AS build

WORKDIR /build

# Install Maven
RUN apt-get update && \
    apt-get install -y maven && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

COPY pom.xml .
COPY src ./src

RUN mvn package

FROM eclipse-temurin:25-jre

ENV APP_PORT=8080
ENV MODEL_HOST=http://model-service:8081

WORKDIR /app

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]