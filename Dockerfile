FROM maven:3.9.16-eclipse-temurin-21-noble@sha256:52ccc14841f74717481b69c5b201aae99f6eeb5164e8aaa2a6134e371ba4cd23 AS build
WORKDIR /build
COPY pom.xml .
COPY src/main/ src/main/
RUN mvn --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

FROM eclipse-temurin:21.0.12.1_1-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c
WORKDIR /app
COPY --from=build /build/target/chess-backend-*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
