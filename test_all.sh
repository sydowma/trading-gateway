#!/bin/bash
ps aux | grep java | grep trading | grep -v grep | awk '{print $2}' | xargs kill -9 2>/dev/null || true
lsof -ti:9090 | xargs kill -9 2>/dev/null || true
sleep 2
java --enable-preview -jar target/trading-gateway-1.0.0.jar > /tmp/gateway12.log 2>&1 &
sleep 40
echo "=== API Status ==="
curl -s http://localhost:9090/api/status
echo ""
echo "=== Metrics ==="
curl -s http://localhost:9090/metrics 2>/dev/null | grep -E "gateway_messages_(received|published)_total" | head -15
