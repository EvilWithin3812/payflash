# Same PostgreSQL version as the databases, so pg_dump matches the server.
FROM postgres:16-alpine
COPY backup.sh /usr/local/bin/backup.sh
# Strip Windows line endings in case the file was checked out with CRLF.
RUN sed -i 's/\r$//' /usr/local/bin/backup.sh && chmod +x /usr/local/bin/backup.sh
USER postgres
ENTRYPOINT ["/usr/local/bin/backup.sh"]
# Unhealthy if no dump was written in the last 26 hours.
HEALTHCHECK --interval=1h --timeout=10s --start-period=5m \
  CMD find /backups -name '*.dump' -mmin -1560 | grep -q . || exit 1
