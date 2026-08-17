# Build the base node image first (see ../README.md, "Building Docker image"), tagged hearth-node by default.
ARG baseImage=hearth-node
FROM $baseImage
# Nothing in Application.scala sets a default HRP (see CLAUDE.md, "node-it fixtures") - a node crashes the first
# time it renders or parses any address unless -Dhearth.hrp is set some other way. entrypoint.sh appends JAVA_OPTS.
ENV JAVA_OPTS="-Dhearth.hrp=phrth"
COPY hearth.custom.conf /etc/hearth/hearth.conf
