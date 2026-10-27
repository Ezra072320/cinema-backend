#!/usr/bin/env bash
set -euo pipefail
# Updating these definitions is idempotent. Never delete topics or consumer offsets.
sh mqadmin updateTopic -n "$NAMESRV_ADDR" -c CinemaLocal -t cinema-order-events -r 2 -w 2 -a +message.type=NORMAL
sh mqadmin updateTopic -n "$NAMESRV_ADDR" -c CinemaLocal -t cinema-order-timeouts -r 2 -w 2 -a +message.type=DELAY
sh mqadmin updateSubGroup -n "$NAMESRV_ADDR" -c CinemaLocal -g cinema-notify-orders -r 5 -m true
sh mqadmin updateSubGroup -n "$NAMESRV_ADDR" -c CinemaLocal -g cinema-benefit-orders -r 5 -m true
sh mqadmin updateSubGroup -n "$NAMESRV_ADDR" -c CinemaLocal -g cinema-trade-timeouts -r 5 -m true

sh mqadmin updateTopic -n "$NAMESRV_ADDR" -c CinemaLocal -t cinema-movie-events -r 2 -w 2 -a +message.type=NORMAL
sh mqadmin updateSubGroup -n "$NAMESRV_ADDR" -c CinemaLocal -g cinema-search-movies -r 5 -m true

# The gRPC Proxy supplies MAX for a group's first POP. Set only missing progress before consumers
# start; group consumeFromMinEnable alone is insufficient for this protocol. Existing offsets stay.
mkdir -p /tmp/cinema-offset-init
javac -J-Xmx96m -cp "$ROCKETMQ_HOME/lib/*" -d /tmp/cinema-offset-init /init/InitializeConsumerOffsets.java
java -Xms32m -Xmx96m -XX:ActiveProcessorCount=2 -cp "$ROCKETMQ_HOME/lib/*:/tmp/cinema-offset-init" InitializeConsumerOffsets
