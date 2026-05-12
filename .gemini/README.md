# Sandbox implementation for Gemini CLI

Based on https://medium.com/google-cloud/secure-gemini-cli-for-cloud-development-488b23dedf29

1. Make sure that sandbox.Dockerfile has everything that you need installed
2. Run build-docker-sandbox.sh to create the docker image
3. Tell Gemini to use the sandbox by setting the environment variable: `export GEMINI_SANDBOX=docker`
3. Start Gemini - it will use the docker image as a sandbox.
You should find that the CLI reports "sandbox" as "sandbox-0.37.2-0" (version number may differ). If it reports the sandbox as "no sandbox" then something has gone wrong.


