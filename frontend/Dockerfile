FROM registry.access.redhat.com/ubi9/nodejs-22-minimal

WORKDIR /opt/app-root/src

ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# Turbopack は linux/amd64 クロスビルド（QEMU）で不安定なため、コンテナ内は webpack を使用
RUN npm run build -- --webpack

EXPOSE 3000

CMD ["npm", "run", "start", "--", "-H", "0.0.0.0", "-p", "3000"]
