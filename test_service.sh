#!/bin/bash
java --enable-preview -jar target/trading-gateway-1.0.0.jar > /tmp/gateway7.log 2>&1 &
sleep 25
curl -s http://localhost:9090/api/status
