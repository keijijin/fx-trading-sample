FROM apache/kafka:3.9.1

USER root

RUN mkdir -p /opt/kafka/config /tmp/kraft-combined-logs \
    && chgrp -R 0 /opt/kafka /tmp/kraft-combined-logs \
    && chmod -R g=u /opt/kafka /tmp/kraft-combined-logs
