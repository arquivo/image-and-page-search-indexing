FROM maven:3.9.9-eclipse-temurin-8

# Match the host user so files written to the bind-mounted repo are not root-owned
ARG UID=1000
ARG GID=1000
RUN groupadd -o -g ${GID} dev && useradd -o -m -u ${UID} -g ${GID} dev \
    && mkdir -p /m2 /data /out && chown dev:dev /m2 /out

USER dev

# Same as the cluster nodes (file.encoding = UTF-8)
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 \
    MAVEN_CONFIG=/m2 \
    MAVEN_OPTS=-Dmaven.repo.local=/m2/repository

WORKDIR /workspace
