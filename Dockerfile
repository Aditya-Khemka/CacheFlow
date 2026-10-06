FROM maven:3.9-eclipse-temurin-25 AS build
#Start from an image that already has Maven 3.9 and Java 25 installed.
#name this image as 'build'

#equivalent : mkdir /app => cd /app
WORKDIR /app  

#copies pm.xml from local machine to /app in the container
COPY pom.xml .    

# download all dependencies listed in pom.xml
RUN mvn dependency:go-offline -B    

# now copy the source code (changes often, so it comes later)
COPY src ./src

# compile and package into a runnable jar
RUN mvn package -DskipTests -B
#-DskipTests skips unit tests


# Build design decision 
# Aam Zindagi       : COPY *.* 
# mentos Zindagi    : First copy pom.xml and then copy the rest of the files
# Why : Docker runs each line as a seperate layer. For every change, it will re-run the layers above it. 
# So if we copy all files at once, any change in any file will cause the entire build to re-run. 
# By copying pom.xml first, we can take advantage of Docker's caching mechanism. 
# If pom.xml hasn't changed, Docker will use the cached layer for the build, saving time and resources.


# Also Stage 1 only has Maven ,JDK and the source code. 
# It then uses them to create the jar file, but does NOT run it.
# We do it in stage 2, which has only the JRE and the jar file.


# STAGE 2: RUN

# A second FROM starts a brand new image (this gets the image for JRE).
FROM eclipse-temurin:25-jre

# Create /app in this new image and work from there.
WORKDIR /app

COPY --from=build /app/target/cacheflow-0.0.1-SNAPSHOT.jar app.jar
# from the 'build' image copy 'cacheflow-0.0.1-SNAPSHOT.jar' and rename it to 'app.jar'

# Settings come from ENVIRONMENT VARIABLES, not from command-line args.
# Why : Render (and other hosts) can't pass args like docker-compose's `command:` line,
# but every host lets you set env vars. So a tiny shell turns env vars into CLI flags.
#
#   env var       -> flag            default
#   ORIGIN        -> --origin        none : container exits with "ORIGIN is required"
#   PORT          -> --port          8080 (Render injects its own PORT, usually 10000)
#   TTL           -> --ttl           15   (minutes)
#   MAX_ENTRIES   -> --max-entries   100
#
# How the shell syntax works:
#   ${PORT:-8080}                 use $PORT ; if it's unset OR empty, use 8080
#   ${ORIGIN:?ORIGIN is required} use $ORIGIN ; if unset OR empty, print the message and exit non-zero
#   "--port" "$PORT"              two separate tokens on purpose: the app's extractPort() only
#                                 understands `--port 10000`, not `--port=10000`
#   exec java ...                 REPLACES the shell with Java, so Java becomes PID 1 and receives
#                                 `docker stop`'s SIGTERM directly -> clean shutdown -> the
#                                 @PreDestroy cache save to data/cache.json actually runs
#   "$@" and the trailing "--"    with `sh -c 'script' a b c`, the word after the script becomes $0
#                                 and the rest become $@. "--" fills the $0 slot, so anything
#                                 you add after the image name (docker run cacheflow --foo) lands
#                                 in "$@" and is appended to the java command.
#
# Example: docker run -e ORIGIN=https://dummyjson.com -e TTL=30 -p 3000:8080 cacheflow
ENTRYPOINT ["sh", "-c", "exec java -jar app.jar --origin \"${ORIGIN:?ORIGIN is required}\" --port \"${PORT:-8080}\" --ttl \"${TTL:-15}\" --max-entries \"${MAX_ENTRIES:-100}\" \"$@\"", "--"]