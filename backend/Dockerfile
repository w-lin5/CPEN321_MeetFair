# syntax=docker/dockerfile:1

FROM node:22-alpine
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# sets process.env.PORT
ENV PORT=3000 
ENV NODE_ENV=development

# documents that the container listens on port 3000
EXPOSE 3000

CMD ["npm", "run", "dev"]
