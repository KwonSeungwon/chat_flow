FROM openjdk:21-jdk-slim

ARG SERVICE_NAME
ENV SERVICE_NAME=${SERVICE_NAME}

WORKDIR /app

COPY ${SERVICE_NAME}/build/libs/${SERVICE_NAME}-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]