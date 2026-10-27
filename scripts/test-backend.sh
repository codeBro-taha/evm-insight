#!/usr/bin/env bash
# Runs the backend tests in a pinned Maven + JDK 21 container. Needs only Docker.
# Reports land in backend/target/surefire-reports and backend/target/site/jacoco.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p "$HOME/.m2"
docker run --rm \
  -v "$PWD/backend":/app \
  -v "$HOME/.m2":/root/.m2 \
  -w /app \
  maven:3.9-eclipse-temurin-21 \
  mvn -B test
