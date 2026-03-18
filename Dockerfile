FROM docker.io/eclipse-temurin:25-jdk AS builder

WORKDIR /build

# Download JPEXS ffdec_lib release, extract, and install to local maven repo
# We grab the latest release dynamically
RUN apt-get update && apt-get install -y curl jq unzip maven
RUN LATEST_TAG=version25.1.3 && \
    VERSION=25.1.3 && \
    curl -L "https://github.com/jindrapetrik/jpexs-decompiler/releases/download/${LATEST_TAG}/ffdec_lib_${VERSION}.zip" -o ffdec_lib.zip && \
    unzip ffdec_lib.zip -d ffdec_lib_dir && \
    mvn install:install-file -Dfile=ffdec_lib_dir/ffdec_lib.jar -DgroupId=com.jpexs -DartifactId=ffdec_lib -Dversion=25.1.3 -Dpackaging=jar

COPY pom.xml .
RUN mvn dependency:go-offline || true

COPY src ./src
RUN mvn clean package -DskipTests

FROM docker.io/eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /build/target/ffdec-mcp-server-1.0.0.jar ./server.jar

# Include license and compliance files for ffdec_lib (LGPLv3)
COPY LICENSES /licenses

# Disable logging to stdout to prevent JSON-RPC stream corruption
# The server is already designed to log to stderr
ENTRYPOINT ["java", "-jar", "server.jar"]
