# 1) TypeScript client
FROM node:24-alpine AS client
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# 2) Spring Boot jar (Java 21)
FROM eclipse-temurin:21-jdk AS server
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sh mvnw -B -q dependency:go-offline
COPY src src
COPY --from=client /build/src/main/resources/static/app.js src/main/resources/static/app.js
RUN sh mvnw -B -q package -DskipTests

# 3) Runtime: JRE + docker CLI. The CLI talks to the host daemon through the mounted socket and starts
#    a separate locked-down container per execution; user code never runs in this container.
FROM eclipse-temurin:21-jre
COPY --from=docker:28-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=server /build/target/realtime-devboard-0.0.1-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
