FROM bitnami/kafka:latest
USER root
RUN apt-get update && apt-get install -y netcat-openbsd
COPY init_topics.sh /usr/bin/init_topics.sh
RUN chmod +x /usr/bin/init_topics.sh
ENTRYPOINT ["/usr/bin/init_topics.sh"]