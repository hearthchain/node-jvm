# Build the base node image first (see ../README.md, "Building Docker image"), tagged hearth-node by default.
ARG baseImage=hearth-node
FROM $baseImage
COPY hearth.custom.conf /etc/hearth/hearth.conf
