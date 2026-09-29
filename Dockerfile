# ==========================================
# Stage 1: Build the application
# ==========================================
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Copy gradle files for caching layers
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./

# Fix CRLF line endings from Windows and ensure executable permissions
RUN sed -i 's/\r$//' ./gradlew && chmod +x ./gradlew

# Copy the rest of the application source code
COPY src ./src
COPY keys.properties* ./

# Build the distribution allocation
RUN ./gradlew installDist --no-daemon

# ==========================================
# Stage 2: Create the runtime image
# ==========================================
FROM mcr.microsoft.com/playwright/java:v1.59.0-noble AS runtime

# Install native dependencies required for OpenCV/OpenPNP, ffmpeg, and gifsicle for GIF optimization
RUN apt-get update && apt-get install -y \
    libopencv-dev \
    ffmpeg \
    gifsicle \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the built distribution contents directly into /app (flattens structure)
COPY --from=build /app/build/install/* /app/

# Tell Playwright to look for the system browsers baked into this image
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

EXPOSE 3000

# Dynamically execute the generated start script
CMD ["sh", "-c", "./bin/* 2>&1"]