FROM node:24-bookworm-slim
WORKDIR /app
COPY package.json reports.mjs github.mjs server.mjs admin.mjs ./
ENV NODE_ENV=production
EXPOSE 3000
CMD ["node", "server.mjs"]
