# build 단계
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install
COPY . .
# 카카오맵 앱키는 빌드 타임에 번들에 주입된다 (compose가 루트 .env 값을 build arg로 전달)
ARG VITE_KAKAO_APP_KEY=
ENV VITE_KAKAO_APP_KEY=${VITE_KAKAO_APP_KEY}
RUN npm run build

# serve 단계
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
