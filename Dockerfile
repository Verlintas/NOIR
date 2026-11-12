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
# Both base images are pinned by version and digest; Dependabot moves the pins.
FROM eclipse-temurin:24.0.2_12-jdk-noble@sha256:dacac8e9a0df0d2bd24e702b4431132875c249930b70555ebd7ca285b5bee684 AS build
WORKDIR /src
COPY . .
RUN ./gradlew :cli:installDist --no-daemon --console=plain

FROM eclipse-temurin:24.0.2_12-jre-noble@sha256:b416d02335e702b0403ff280de9475a3348e29382285969c9d4e17862ce632e7
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

# The container stays root on purpose: adb has to open the USB device nodes
# passed through from the host, and those belong to root. With the documented
# flags that means every USB device on the host, not only the phone, so run
# only an image you built yourself.
#
# Reports land in ./noir-out relative to the working directory; mount it.
WORKDIR /work
ENTRYPOINT ["noir"]
