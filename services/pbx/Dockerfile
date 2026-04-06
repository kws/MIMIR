FROM debian:bullseye-slim

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        asterisk \
        asterisk-core-sounds-en \
        asterisk-moh-opsound-gsm \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY asterisk/ /etc/asterisk/

EXPOSE 5060/udp 8088 10000-10099/udp

CMD ["asterisk", "-f", "-U", "asterisk", "-G", "asterisk", "-vvv"]
