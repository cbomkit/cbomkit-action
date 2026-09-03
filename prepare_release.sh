#!/usr/bin/env bash

# Exit immediately if a command exits with a non-zero status
set -e

# 1. Validate Input
VERSION=$1
SEMVER_REGEX="^v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$"

if [ -z "$VERSION" ]; then
    echo "ERROR: No version provided."
    echo "Usage: $0 <semver-version>"
    exit 1
fi

if [[ ! "$VERSION" =~ $SEMVER_REGEX ]]; then
    echo "ERROR: Provided version '$VERSION' does not match SemVer format (e.g., v1.2.3 or v1.2.3-RC1)."
    exit 1
fi

echo "Preparing release for version: $VERSION"

echo "Updating Maven versions..."
mvn versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false
mvn versions:commit

BUILD_FILE=".github/workflows/build.yaml"
if [ -f "$BUILD_FILE" ]; then
    LAST_VERSION=$(git tag --sort=-v:refname | head -n 1)
    echo "Updating version in $BUILD_FILE to $LAST_VERSION ..."
    sed -i.bak -E "s|uses: cbomkit/cbomkit-action@.*'|uses: cbomkit/cbomkit-action@${LAST_VERSION}'|" "$BUILD_FILE" && rm -f "${BUILD_FILE}.bak"
else
    echo "Warning: $BUILD_FILE not found. Skipping builder version update."
fi

ACTION_FILE="action.yml"
if [ -f "$ACTION_FILE" ]; then
    echo "Updating version in $ACTION_FILE..."
    sed -i.bak -E "s|image: 'docker://ghcr.io/cbomkit/cbomkit-action:[^']*'|image: 'docker://ghcr.io/cbomkit/cbomkit-action:${VERSION}'|" "$ACTION_FILE" && rm -f "${ACTION_FILE}.bak"
else
    echo "Warning: $ACTION_FILE not found. Skipping action version update."
fi

echo "Staging changes..."
git add pom.xml "$ACTION_FILE" "$BUILD_FILE"

echo "Committing changes..."
git commit -m "chore: bump version to $VERSION"

echo "Pushing to remote repository..."
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
git push origin "$CURRENT_BRANCH"

echo "Successfully updated and pushed version $VERSION!"

