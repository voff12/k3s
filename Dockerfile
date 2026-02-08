FROM eclipse-temurin:17-jdk-jammy
VOLUME /tmp
ARG JAR_FILE=target/k3s-1.0.0-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app.jar"]
