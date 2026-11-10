FROM ghcr.io/sagernet/sing-box:v1.14.4

RUN apk add --no-cache python3 iproute2
COPY gateway.py /usr/local/bin/artex-egress-gateway
RUN chmod 755 /usr/local/bin/artex-egress-gateway

ENTRYPOINT ["python3", "/usr/local/bin/artex-egress-gateway"]
