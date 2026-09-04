FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN apk add --no-cache maven && \
    mvn -q package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=builder /build/target/razorpay-java-testapp-1.0-SNAPSHOT.jar app.jar

# Env var names match server.yml placeholders exactly.
ENV RAZORPAY_KEY_ID=""
ENV RAZORPAY_SECRET=""
ENV RAZORPAY_WEBHOOK_SECRET=""

RUN cat > server.yml << 'EOF'
apiKey: ${RAZORPAY_KEY_ID}
secretKey: ${RAZORPAY_SECRET}
webhookSecret: ${RAZORPAY_WEBHOOK_SECRET}

server:
  applicationConnectors:
    - type: http
      port: 8080
  adminConnectors:
    - type: http
      port: 8081

logging:
  level: INFO
  appenders:
    - type: console
EOF

EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "app.jar", "server", "server.yml"]
