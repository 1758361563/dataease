FROM mcr.microsoft.com/playwright:v1.63.0-noble

WORKDIR /app
ENV NODE_ENV=production
ENV COMPANY_FEISHU_DRY_RUN=true
ENV FEISHU_LOG_DIR=/var/log/feishu-shot
ENV TZ=Asia/Shanghai

COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src
RUN mkdir -p /var/log/feishu-shot

EXPOSE 8091
CMD ["node", "src/index.mjs"]
