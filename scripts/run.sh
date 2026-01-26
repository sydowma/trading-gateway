#!/bin/bash
set -e

# Default values
GATEWAY_ID="${GATEWAY_ID:-gateway-0}"
EXCHANGES="${EXCHANGES:-binance:true:ticker,trade,book;okx:true:ticker,trade;bybit:true:ticker,trade}"
SYMBOLS="${SYMBOLS:-BTCUSDT:binance,okx,bybit;ETHUSDT:binance,okx}"
AERON_DIR="${AERON_DIR:-/dev/shm/trading-gateway-${GATEWAY_ID}}"
HEALTH_CHECK_MS="${HEALTH_CHECK_MS:-5000}"
RECONNECT_MAX_RETRIES="${RECONNECT_MAX_RETRIES:-10}"

echo "========================================"
echo "  Trading Gateway"
echo "========================================"
echo "Gateway ID: ${GATEWAY_ID}"
echo "Exchanges: ${EXCHANGES}"
echo "Symbols: ${SYMBOLS}"
echo "Aeron Dir: ${AERON_DIR}"
echo "========================================"

# Create logs directory
mkdir -p logs

# Export environment variables
export GATEWAY_ID
export EXCHANGES
export SYMBOLS
export AERON_DIR
export HEALTH_CHECK_MS
export RECONNECT_MAX_RETRIES

# Find the JAR file
JAR_FILE="target/trading-gateway-1.0.0.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "Error: JAR file not found at $JAR_FILE"
    echo "Please run: mvn clean package -DskipTests"
    exit 1
fi

# Run the gateway
java --enable-preview -jar "$JAR_FILE"
