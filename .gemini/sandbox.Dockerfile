# Start from the official Gemini CLI sandbox image with proper version
ARG GEMINI_CLI_VERSION 0.37.2
FROM us-docker.pkg.dev/gemini-code-dev/gemini-cli/sandbox:${GEMINI_CLI_VERSION}

# Switch to root to install system dependencies (gcloud)
USER root

# Install Google Cloud SDK, Git, and prerequisites
RUN apt-get update && apt-get install -y curl apt-transport-https ca-certificates gnupg git && \
    echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" | tee -a /etc/apt/sources.list.d/google-cloud-sdk.list && \
    curl https://packages.cloud.google.com/apt/doc/apt-key.gpg | apt-key --keyring /usr/share/keyrings/cloud.google.gpg add - && \
    apt-get update && apt-get install -y google-cloud-cli

# Install vim
RUN apt-get install -y vim

# Switch back to the non-root user (the official sandbox image uses 'node' as the default user)
USER node
WORKDIR /workspace
