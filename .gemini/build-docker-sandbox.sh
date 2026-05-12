# Get the base name the CLI looks for
export IMAGE_BASE_NAME="us-docker.pkg.dev/gemini-code-dev/gemini-cli/sandbox"

# Get your currently installed Gemini CLI version (e.g., 0.37.2)
export GEMINI_CLI_VERSION=$(gemini --version)

# Combine them
export IMAGE_NAME="${IMAGE_BASE_NAME}:${GEMINI_CLI_VERSION}"

# Build your custom sandbox image
docker build \
  --build-arg GEMINI_CLI_VERSION=$GEMINI_CLI_VERSION \
  -t "${IMAGE_NAME}" \
  -f .gemini/sandbox.Dockerfile .
