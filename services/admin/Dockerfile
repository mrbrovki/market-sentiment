FROM apache/kafka:latest

USER root

# Copy your bash-based init script
COPY init_topics.sh /usr/local/bin/init_topics.sh
RUN chmod +x /usr/local/bin/init_topics.sh

ENTRYPOINT ["/usr/local/bin/init_topics.sh"]
