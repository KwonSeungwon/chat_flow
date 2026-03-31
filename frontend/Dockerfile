# Flutter Web — pre-built locally (flutter build web --release)
# Run: flutter build web --release --web-renderer canvaskit
# Then: docker buildx build --platform linux/amd64 -t chatflow-frontend:latest --load frontend/

FROM nginx:alpine AS production-stage

# 빌드된 Flutter Web 파일 복사
COPY build/web /usr/share/nginx/html

# nginx 설정 파일 복사
COPY nginx.conf /etc/nginx/nginx.conf

RUN chown -R nginx:nginx /usr/share/nginx/html && \
    chown -R nginx:nginx /var/cache/nginx && \
    chown -R nginx:nginx /var/log/nginx && \
    touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid

USER nginx

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
