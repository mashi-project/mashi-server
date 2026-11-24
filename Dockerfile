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
COPY mashis-firebase-adminsdk-fbsvc-2bfb99f3d9.json* ./

# Build the distribution allocation
RUN ./gradlew installDist --no-daemon

# ==========================================
# Stage 2: Build gifsicle with --lossy support (giflossy fork)
# The stock Ubuntu gifsicle package does NOT support --lossy.
# Same base as the runtime image (Ubuntu 24.04 "noble"), so the binary is compatible.
# ==========================================
FROM ubuntu:24.04 AS gifsicle
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential autoconf automake git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
RUN git clone --depth 1 https://github.com/kornelski/giflossy.git . \
    && autoreconf -i \
    && ./configure --disable-gifview --disable-gifdiff --prefix=/opt/giflossy \
    && make -j"$(nproc)" \
    && make install \
    # Fail the image build early if the binary has no lossy option
    && /opt/giflossy/bin/gifsicle --help | grep -q -- '--lossy'

# ==========================================
# Stage 3: Create the runtime image
# ==========================================
FROM mcr.microsoft.com/playwright/java:v1.59.0-noble AS runtime

# Install native dependencies required for OpenCV/OpenPNP and ffmpeg
# (gifsicle comes from the giflossy build stage, not from apt)
RUN apt-get update && apt-get install -y \
    libopencv-dev \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# Lossy-capable gifsicle used by AnimCombiner.optimizeGif()
COPY --from=gifsicle /opt/giflossy/bin/gifsicle /usr/local/bin/gifsicle

WORKDIR /app

# Copy the built distribution contents directly into /app (flattens structure)
COPY --from=build /app/build/install/* /app/

# Copy the Firebase service account JSON into the runtime root directory
COPY --from=build /app/mashis-firebase-adminsdk-fbsvc-2bfb99f3d9.json* ./

# Tell Playwright to look for the system browsers baked into this image
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

EXPOSE 3000

# Dynamically execute the generated start script
CMD ["sh", "-c", "./bin/* 2>&1"]