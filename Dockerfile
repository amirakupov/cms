FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

VOLUME ["/data/uploads"]

ENV JAVA_OPTS=""
# Must match the VOLUME above. application.yml deliberately has no default here so a
# local run fails loudly instead of writing uploads somewhere unexpected; the image is
# the only place that knows the container path.
ENV APP_UPLOAD_DIR=/data/uploads
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]