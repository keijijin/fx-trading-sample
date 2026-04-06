FROM postgres:16

USER 0

RUN mkdir -p /var/lib/postgresql/data /var/run/postgresql /docker-entrypoint-initdb.d \
    && chgrp -R 0 /var/lib/postgresql /var/run/postgresql /docker-entrypoint-initdb.d \
    && chmod -R g=u /var/lib/postgresql /var/run/postgresql /docker-entrypoint-initdb.d
