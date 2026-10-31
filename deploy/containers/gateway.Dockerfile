# syntax=docker/dockerfile:1
FROM debian:trixie-slim@sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4
# Session CA trust exists only in this build step; never in a runtime layer.
RUN --mount=type=secret,id=build_ca,required=true,mode=0444 \
    sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources && \
    apt-get -o Acquire::https::CaInfo=/run/secrets/build_ca update && \
    apt-get -o Acquire::https::CaInfo=/run/secrets/build_ca install -y --no-install-recommends python3-minimal iproute2 nftables wireguard-tools && \
    rm -rf /var/lib/apt/lists/* /var/cache/apt/*
COPY gateway.py /usr/local/lib/field-notes/gateway.py
LABEL org.fieldnotes.component="gateway" org.fieldnotes.runtime="1"
ENTRYPOINT ["python3","-B","/usr/local/lib/field-notes/gateway.py"]
