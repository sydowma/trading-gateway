#!/bin/bash
ps aux | grep java | grep trading | grep -v grep | awk '{print $2}' | xargs kill -9 2>/dev/null || true
lsof -ti:9090 | xargs kill -9 2>/dev/null || true
sleep 2
java --enable-preview -jar target/trading-gateway-1.0.0.jar > /tmp/gateway15.log 2>&1 &
sleep 50
echo "=== API Status ==="
curl -s http://localhost:9090/api/status
echo ""
echo "=== Metrics (received) ==="
curl -s http://localhost:9090/metrics 2>/dev/null | grep "gateway_messages_received_total" | head -15
echo ""
echo "=== Metrics (published) ==="
curl -s http://localhost:9090/metrics 2>/dev/null | grep "gateway_messages_published_total" | head -15
