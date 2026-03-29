FROM eclipse-temurin:21-jre-jammy

ARG SERVICE_NAME
ENV SERVICE_NAME=${SERVICE_NAME}

WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

COPY ${SERVICE_NAME}/build/libs/${SERVICE_NAME}-1.0.0.jar app.jar

RUN chown appuser:appgroup app.jar

ARG SERVICE_PORT=8080
EXPOSE ${SERVICE_PORT}

USER appuser

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
