FROM prom/prometheus:v3.15.1@sha256:fa3302279710be2f21a8301ec17a8b4f2301fd829bd113839b3b6b02b6f191a2 AS probe
FROM grafana/loki:3.7.9@sha256:0db216b630ae1f348a1584c67f3cead8b4c1fc839169a39501db664744158e28
# Loki's distroless image has no shell/wget. This pinned BusyBox is statically linked.
COPY --from=probe /bin/busybox /busybox
