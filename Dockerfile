FROM eclipse-temurin:25-jdk-alpine

# Install bash for the run script
RUN apk add --no-cache bash

# Create app directory
WORKDIR /app

# Copy JAR file
COPY target/trading-gateway-1.0.0.jar /app/trading-gateway.jar

# Create logs directory
RUN mkdir -p /app/logs

# Set environment variables
ENV GATEWAY_ID=gateway-0 \
    EXCHANGES=binance:true:ticker,trade,book;okx:true:ticker,trade;bybit:true:ticker,trade \
    SYMBOLS=BTCUSDT:binance,okx,bybit;ETHUSDT:binance,okx \
    AERON_DIR=/dev/shm/trading-gateway-gateway-0 \
    HEALTH_CHECK_MS=5000 \
    RECONNECT_MAX_RETRIES=10

# Expose no ports (uses IPC only)
# If you need HTTP monitoring in the future, expose it here

# Run the application
ENTRYPOINT ["sh", "-c", "java --enable-preview -jar /app/trading-gateway.jar"]
