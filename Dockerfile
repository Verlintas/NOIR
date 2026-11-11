# NOIR in a container: a JRE, adb and NOIR itself, so a Linux host needs
# nothing but Docker.
#
#   docker build -t noir .
#   docker run --rm -it \
#     --device-cgroup-rule='c 189:* rmw' -v /dev/bus/usb:/dev/bus/usb \
#     -v noir-home:/root -v "$PWD:/work" noir
#
# README ("方式三：Docker") explains every flag.

# Build and runtime share one Java release: Gradle targets the JDK it runs on,
# so a newer build JDK would produce classes the runtime cannot load.
FROM eclipse-temurin:17-jdk-noble AS build
WORKDIR /src
COPY . .
RUN ./gradlew :cli:installDist --no-daemon --console=plain

FROM eclipse-temurin:17-jre-noble
LABEL org.opencontainers.image.title="NOIR" \
      org.opencontainers.image.description="Android leak audit" \
      org.opencontainers.image.licenses="MIT"

# adb comes from the distribution: Google's platform-tools zip is x86_64 only,
# the package also exists for arm64. jadx is not baked in; `noir setup`
# downloads it into the /root volume when deep/deepscan are wanted.
RUN apt-get update \
    && apt-get install -y --no-install-recommends adb \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/cli/build/install/noir /opt/noir
COPY LICENSE /opt/noir/LICENSE
ENV PATH="/opt/noir/bin:${PATH}"

# Reports land in ./noir-out relative to the working directory; mount it.
WORKDIR /work
ENTRYPOINT ["noir"]
