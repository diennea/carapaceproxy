FROM ubuntu:latest

ARG TARBALL
RUN test -n "$TARBALL"

ENV ADMIN_PORT=8001
ENV LISTENER_HTTP_PORT=8089
ENV LISTENER_HTTPS_PORT=4089
ENV CARAPACE_http.admin.host=0.0.0.0

EXPOSE $ADMIN_PORT
EXPOSE $LISTENER_HTTP_PORT
EXPOSE $LISTENER_HTTPS_PORT

RUN apt-get update \
     && apt-get -y dist-upgrade \
     && apt-get -y install --no-install-recommends openjdk-17-jdk-headless netcat dnsutils less curl unzip \
     && apt-get -y --purge autoremove \
     && apt-get autoclean \
     && apt-get clean \
     && rm -rf /var/lib/apt/lists/*


ADD ${TARBALL} /
RUN mkdir /carapace
RUN unzip /carapace-server*.zip && cd /carapace-server*/ && mv ./* /carapace && rm -rf /carapace-server-*

COPY entrypoint.sh /carapace/entrypoint.sh

WORKDIR /carapace
ENTRYPOINT ["/carapace/entrypoint.sh"]
CMD ["/carapace/bin/service", "server", "console"]