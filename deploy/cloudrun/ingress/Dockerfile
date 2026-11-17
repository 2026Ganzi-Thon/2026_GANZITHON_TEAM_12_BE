# syntax=docker/dockerfile:1
# Cloud Run 진입 컨테이너: 프론트 정적 파일 + 백엔드 프록시(nginx.conf).
# 프론트 빌드 결과를 넣을 때: docker build --build-context site=<프론트 dist 경로> deploy/cloudrun/ingress
# 넘기지 않으면 placeholder/의 배포 확인용 화면이 들어간다.

FROM scratch AS site
COPY placeholder/ /

FROM nginx:1.28-alpine
RUN rm -rf /usr/share/nginx/html/*
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=site / /usr/share/nginx/html/
EXPOSE 8080
