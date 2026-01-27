#!/bin/bash
ps aux | grep java | grep trading | grep -v grep | awk '{print $2}' | xargs kill -9 2>/dev/null || true
lsof -ti:9090 | xargs kill -9 2>/dev/null || true
sleep 2
java --enable-preview -jar target/trading-gateway-1.0.0.jar > /tmp/gateway13.log 2>&1 &
sleep 45
echo "=== API Status ==="
curl -s http://localhost:9090/api/status
echo ""
echo "=== Binance Logs ==="
grep -E "Binance.*Subscribed|Binance.*Raw|Binance.*Connected" /tmp/gateway13.log | tail -15
