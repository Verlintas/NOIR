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
FROM eclipse-temurin:17.0.21_9-jdk-noble@sha256:1bedb04434bd275ef072355e9caec2a22be5240fbe82ad51a59323f2bbcab4a6 AS build
WORKDIR /src
COPY . .
RUN ./gradlew :cli:installDist --no-daemon --console=plain

FROM eclipse-temurin:17.0.21_9-jre-noble@sha256:042c05a77eb915684fc8c92cfbbef4fd6f8c24ca7d934d1a7db3543453aaa077
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
