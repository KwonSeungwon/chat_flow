FROM docker.elastic.co/elasticsearch/elasticsearch:8.11.0

# Install Nori (Korean) analysis plugin
RUN elasticsearch-plugin install analysis-nori

# Copy custom configuration
COPY config/elasticsearch.yml /usr/share/elasticsearch/config/elasticsearch.yml

# Set proper permissions
USER root
RUN chown -R elasticsearch:elasticsearch /usr/share/elasticsearch/config
USER elasticsearch