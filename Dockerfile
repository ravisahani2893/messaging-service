# Messaging Service image. Two stages: build the jar, then run it on a small JRE.
#
#   docker build -t messaging-service .
#   docker run --rm -p 9090:9090 messaging-service

# ---- Stage 1: build ---------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy the build files first and download dependencies, so this slow step is
# cached and only repeats when the build files change.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

# Then copy the source and build the runnable jar. Tests are run separately
# with ./gradlew test, so they are skipped here to keep the image build fast.
COPY src src
RUN ./gradlew --no-daemon bootJar -x test \
    && cp "$(ls build/libs/*.jar | grep -v -- '-plain.jar' | head -n 1)" /app/app.jar

# ---- Stage 2: run -----------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Do not run as root
RUN useradd --system --no-create-home messaging
COPY --from=build /app/app.jar app.jar
USER messaging

# The TCP port of the messaging server (messaging.tcp.port)
EXPOSE 9090

# Settings can be overridden with environment variables, for example
#   docker run -e MESSAGING_TCP_MAX_CLIENTS=50 ...
ENTRYPOINT ["java", "-jar", "app.jar"]
