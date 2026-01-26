# Trading Gateway

A high-performance quantitative trading gateway that aggregates market data from multiple cryptocurrency exchanges (Binance, OKX, Bybit) and distributes it via Aeron IPC for ultra-low latency consumption by downstream trading strategies.

## Features

- **Multi-Exchange Aggregation**: Connects to Binance, OKX, and Bybit
- **Data Types**: Ticker, Trades, Order Book (depth)
- **Low Latency**: Uses Aeron IPC for intra-machine messaging
- **Unified Data Model**: Normalizes different exchange formats
- **Auto-Reconnect**: Exponential backoff reconnection strategy
- **Health Monitoring**: Built-in connection health checks
- **Easy Deployment**: Single JAR with Docker support

## Architecture

```
Exchange WebSocket APIs
       │
       ▼
Exchange Connectors (Binance, OKX, Bybit)
       │
       ▼
Message Parsers (normalize to unified model)
       │
       ▼
AeronPublisher (JSON over Aeron IPC)
       │
       ▼
Downstream Trading Strategies (Aeron Subscribers)
```

## Stream ID Allocation

Each exchange+data type gets a unique stream ID:

| Exchange | Ticker | Trades | Order Book |
|----------|--------|--------|------------|
| Binance  | 1001   | 1002   | 1003       |
| OKX      | 1011   | 1012   | 1013       |
| Bybit    | 1021   | 1022   | 1023       |

## Quick Start

### Prerequisites

- Java 25 with preview features enabled
- Maven 3.9+
- Linux with `/dev/shm` (shared memory) recommended for Aeron

### Build

```bash
mvn clean package -DskipTests
```

### Run

```bash
# Using the run script
./scripts/run.sh

# Or directly with environment variables
export GATEWAY_ID=gateway-0
export EXCHANGES=binance:true:ticker,trade,book
export SYMBOLS=BTCUSDT:binance
java --enable-preview -jar target/trading-gateway-1.0.0.jar
```

## Configuration

Configuration is done via environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `GATEWAY_ID` | Unique gateway instance identifier | `gateway-0` |
| `EXCHANGES` | Exchange configs | `binance:true:ticker,trade,book;okx:true:ticker,trade;bybit:true:ticker,trade` |
| `SYMBOLS` | Symbol configs | `BTCUSDT:binance,okx,bybit;ETHUSDT:binance,okx` |
| `AERON_DIR` | Aeron directory | `/dev/shm/trading-gateway-{gatewayId}` |
| `HEALTH_CHECK_MS` | Health check interval | `5000` |
| `RECONNECT_MAX_RETRIES` | Max reconnect retries | `10` |

### Exchange Config Format

```
exchange_name:enabled:data_types
```

Examples:
- `binance:true:ticker,trade,book` - Enable all data types for Binance
- `okx:true:ticker,trade` - Enable only ticker and trades for OKX
- `bybit:false:ticker,trade,book` - Disable Bybit

### Symbol Config Format

```
SYMBOL:exchange1,exchange2,exchange3
```

Examples:
- `BTCUSDT:binance,okx,bybit` - Subscribe to BTCUSDT on all exchanges
- `ETHUSDT:binance,okx` - Subscribe to ETHUSDT only on Binance and OKX

### Example Configuration

```bash
# Full configuration example
export GATEWAY_ID=prod-gateway-1
export EXCHANGES="binance:true:ticker,trade,book;okx:true:ticker,trade;bybit:true:ticker"
export SYMBOLS="BTCUSDT:binance,okx,bybit;ETHUSDT:binance,okx;SOLUSDT:binance"
export AERON_DIR=/dev/shm/trading-gateway-prod
export HEALTH_CHECK_MS=3000
export RECONNECT_MAX_RETRIES=20
```

## Docker Deployment

### Using Docker Compose

```bash
docker-compose up -d
```

### Using Docker directly

```bash
# Build image
docker build -t trading-gateway:latest .

# Run container
docker run -d \
  --name trading-gateway \
  -e GATEWAY_ID=gateway-0 \
  -e EXCHANGES=binance:true:ticker,trade,book \
  -e SYMBOLS=BTCUSDT:binance \
  -v /dev/shm:/dev/shm \
  trading-gateway:latest
```

## Data Model

### Ticker

```json
{
  "exchange": "BINANCE",
  "symbol": "BTCUSDT",
  "timestamp": 1704067200000,
  "gatewayTimestamp": 1704067200000000000,
  "lastPrice": 43250.50,
  "bidPrice": 43250.00,
  "askPrice": 43251.00,
  "bidQuantity": 1.5,
  "askQuantity": 2.0,
  "volume24h": 12345.67,
  "change24h": 250.50,
  "changePercent24h": 0.58
}
```

### Trade

```json
{
  "exchange": "BINANCE",
  "symbol": "BTCUSDT",
  "timestamp": 1704067200000,
  "gatewayTimestamp": 1704067200000000000,
  "tradeId": "123456789",
  "price": 43250.50,
  "quantity": 0.5,
  "side": "BUY"
}
```

### OrderBook

```json
{
  "exchange": "BINANCE",
  "symbol": "BTCUSDT",
  "timestamp": 1704067200000,
  "gatewayTimestamp": 1704067200000000000,
  "bids": [
    {"price": 43250.00, "quantity": 1.5},
    {"price": 43249.00, "quantity": 2.0}
  ],
  "asks": [
    {"price": 43251.00, "quantity": 2.0},
    {"price": 43252.00, "quantity": 1.0}
  ],
  "isSnapshot": false
}
```

## Consuming Data with Aeron

Here's a simple example of how to subscribe to the gateway's Aeron streams:

```java
Aeron.Context context = new Aeron.Context()
    .aeronDirectoryName("/dev/shm/trading-gateway-gateway-0");

Aeron aeron = Aeron.connect(context);

// Subscribe to Binance ticker stream (stream ID 1001)
String channel = "aeron:ipc?term-length=128k|alias=gateway-binance-ticker";
int streamId = 1001;

Subscription subscription = aeron.addSubscription(channel, streamId);

while (running) {
    int fragments = subscription.poll(fragment -> {
        byte[] data = new byte[fragment.limit()];
        fragment.buffer().getBytes(fragment.offset(), data);

        // Parse JSON message
        ObjectMapper mapper = new ObjectMapper();
        Ticker ticker = mapper.readValue(data, Ticker.class);

        System.out.println("Received ticker: " + ticker);
    }, 10);

    // ... backpressure handling
}
```

## Monitoring and Logs

### Logs

Logs are written to:
- Console: `STDOUT`
- File: `logs/trading-gateway.log` (rotated daily)

### Health Monitoring

The gateway includes built-in health monitoring that checks:
- Connection status to each exchange
- Message counts per exchange
- Error counts per exchange
- Disconnect events

Status is logged periodically and on state changes.

### Aeron Monitoring

Use `aeron-stat` to monitor Aeron streams:

```bash
# Install Aeron tools
# Then run:
aeron-stat -D /dev/shm/trading-gateway-gateway-0
```

### Prometheus Metrics

The gateway exposes Prometheus metrics on an HTTP endpoint for monitoring and alerting.

**Environment Variable:**
- `METRICS_PORT`: Port for metrics HTTP server (default: `9090`)

**Metrics Endpoints:**
- `http://localhost:9090/metrics` - Prometheus metrics
- `http://localhost:9090/health` - Health check

**Available Metrics:**

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `gateway_messages_received_total` | Counter | exchange, data_type | Total messages received from exchanges |
| `gateway_messages_published_total` | Counter | exchange, data_type | Total messages published to Aeron |
| `gateway_parse_errors_total` | Counter | exchange | Total parsing errors |
| `gateway_connection_errors_total` | Counter | exchange | Total connection errors |
| `gateway_reconnect_attempts_total` | Counter | exchange | Total reconnection attempts |
| `gateway_publication_failures_total` | Counter | - | Total Aeron publication failures |
| `gateway_connection_status` | Gauge | exchange | Connection status (1=connected, 0=disconnected) |
| `gateway_active_subscriptions` | Gauge | exchange | Number of active symbol subscriptions |
| `gateway_message_latency_milliseconds` | Summary | exchange, data_type | Message processing latency |
| `gateway_message_size_bytes` | Histogram | exchange, data_type | Message size distribution |

### REST API

The gateway provides a REST API for querying status and health.

**Environment Variable:**
- `METRICS_PORT`: Port for HTTP server (default: `9090`)

**Available Endpoints:**

| Endpoint | Description |
|----------|-------------|
| `GET /metrics` | Prometheus metrics (plain text) |
| `GET /health` | Simple health check (returns "OK") |
| `GET /api/status` | Detailed gateway status (JSON) |
| `GET /api/health` | Health check with details (JSON) |
| `GET /api/config` | Current configuration (JSON) |

**Example API Responses:**

```bash
# Get gateway status
curl http://localhost:9090/api/status

# Response:
{
  "gatewayId": "gateway-0",
  "uptimeMs": 12345678,
  "exchanges": {
    "binance": {
      "exchange": "BINANCE",
      "connected": true,
      "tickerCount": 12345,
      "tradeCount": 67890,
      "orderBookCount": 34567,
      "totalMessages": 114802,
      "errors": 0,
      "dataTypes": "[TICKER, TRADES, ORDER_BOOK]",
      "subscriptions": 2
    }
  }
}

# Get health status
curl http://localhost:9090/api/health

# Response (healthy):
{
  "healthy": true,
  "message": "All systems operational"
}

# Response (unhealthy):
{
  "healthy": false,
  "message": "BINANCE disconnected"
}
```

## Performance Considerations

1. **Shared Memory**: For best performance, set `AERON_DIR` to `/dev/shm/`
2. **Epoll**: On Linux, Netty automatically uses epoll for better performance
3. **Term Length**: Aeron term length is set to 128KB for low latency
4. **Single-Threaded Publishing**: Each exchange+dataType has its own publication

## Troubleshooting

### Connection Issues

If you see frequent disconnections:
1. Check your network connectivity
2. Increase `RECONNECT_MAX_RETRIES`
3. Check exchange API status pages

### Aeron Driver Issues

If Aeron fails to start:
1. Ensure `AERON_DIR` is writable
2. Check for stale Aeron files: `rm -rf /dev/shm/trading-gateway-*`
3. Verify no other process is using the same Aeron directory

### High Memory Usage

The gateway maintains minimal state in memory. If you see high usage:
1. Check for message backlog in Aeron streams
2. Verify consumers are keeping up with the data rate

## License

MIT License - See LICENSE file for details

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.

## Support

For issues and questions, please open an issue on GitHub.
