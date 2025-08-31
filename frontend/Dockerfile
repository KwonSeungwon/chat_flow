# Node.js 23.5를 기반으로 하는 Alpine Linux 이미지
FROM node:23.5-alpine as build-stage

# 작업 디렉토리 설정
WORKDIR /app

# package.json과 package-lock.json 복사
COPY package*.json ./

# 의존성 설치
RUN npm ci --only=production

# 소스 코드 복사
COPY . .

# 빌드
RUN npm run build

# Production stage
FROM nginx:alpine as production-stage

# 빌드된 파일을 nginx로 복사
COPY --from=build-stage /app/dist /usr/share/nginx/html

# nginx 설정 파일 복사
COPY nginx.conf /etc/nginx/nginx.conf

# 80 포트 노출
EXPOSE 80

# nginx 실행
CMD ["nginx", "-g", "daemon off;"]