#!/bin/sh
# Gradle wrapper script for Unix-based systems

set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="hia-inventory-app"

# Determine the location of the Gradle wrapper jar
if [ -z "$GRADLE_HOME" ]; then
  GRADLE_HOME="$DIR/gradle/wrapper"
fi

# Execute the Gradle wrapper
exec "$GRADLE_HOME/gradle-wrapper.jar" "$@"