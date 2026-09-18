# Development image of the backend, used by infra/compose/compose.yml (`make up`).
#
# The build runs inside the container, so a developer needs Docker and make and nothing
# else to start the stack: no JDK, no Gradle on the host.
#
# Deviation from 17A section 10, which builds this image with Jib. Jib belongs to the CI
# package stage (17A section 11); when it lands, compose can use that image and this file
# can go. Recorded in docs/PROGRESS.md.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# The wrapper first, so the Gradle distribution is downloaded once and cached as a layer.
COPY gradlew ./
COPY gradle ./gradle
# A Windows checkout may hold gradlew with CRLF line ends, which a Linux shell cannot run.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --version --no-daemon

COPY . .
RUN sed -i 's/\r$//' gradlew \
 && ./gradlew :app:bootJar --no-daemon -x test \
 && find app/build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /app.jar \;

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 coop
USER coop
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
