#!/bin/bash
ps aux | grep java | grep trading | grep -v grep | awk '{print $2}' | xargs kill -9 2>/dev/null || true
lsof -ti:9090 | xargs kill -9 2>/dev/null || true
sleep 2
java --enable-preview -jar target/trading-gateway-1.0.0.jar > /tmp/gateway10.log 2>&1 &
sleep 35
curl -s http://localhost:9090/api/status
echo ""
echo "=== OKX Status ==="
grep -E "OKX.*Connected|OKX.*Subscribed|OKX.*tickerCount|OKX.*tradeCount|OKX.*error" /tmp/gateway10.log | tail -10
