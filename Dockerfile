FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN apk add --no-cache maven && \
    mvn -q package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=builder /build/target/razorpay-java-testapp-1.0-SNAPSHOT.jar app.jar

ENV RAZORPAY_API_KEY=""
ENV RAZORPAY_SECRET_KEY=""
ENV RAZORPAY_WEBHOOK_SECRET=""

RUN cat > server.yml << 'EOF'
apiKey: ${RAZORPAY_API_KEY}
secretKey: ${RAZORPAY_SECRET_KEY}
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
