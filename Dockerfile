FROM eclipse-temurin:21.0.12.1_1-jdk-noble@sha256:d1eb0297924c2d5a37ba7042a59ae84a3487e086b077ac054019a423767d4311 AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

RUN chmod +x ./mvnw \
    && ./mvnw --batch-mode --no-transfer-progress -DskipTests package \
    && cp target/invoward-web-app-*.jar /application.jar

FROM eclipse-temurin:21.0.12.1_1-jre-noble@sha256:817f192b914584dac49595ae662461250b21aaa31df8698feac767d08defb3d0 AS runtime

WORKDIR /app

COPY --from=build --chown=10001:10001 /application.jar /app/application.jar

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
