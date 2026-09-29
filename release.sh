#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

VERSION_FILE="gradle.properties"

if [[ ! -f "$VERSION_FILE" ]]; then
    echo "ERROR: $VERSION_FILE not found."
    exit 1
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "ERROR: This is not a Git repository."
    exit 1
fi

BRANCH="$(git branch --show-current)"

if [[ -z "$BRANCH" ]]; then
    echo "ERROR: You are in detached HEAD state."
    exit 1
fi

CURRENT_VERSION="$(grep -E '^mod_version=' "$VERSION_FILE" | head -n1 | cut -d'=' -f2-)"

if [[ -z "$CURRENT_VERSION" ]]; then
    echo "ERROR: Could not find mod_version in $VERSION_FILE."
    exit 1
fi

echo
echo "======================================"
echo "        Mod Release Script"
echo "======================================"
echo
echo "Current version : $CURRENT_VERSION"
echo "Git branch      : $BRANCH"
echo

read -r -p "New version: " NEW_VERSION

if [[ -z "$NEW_VERSION" ]]; then
    echo "ERROR: Version cannot be empty."
    exit 1
fi

if [[ "$NEW_VERSION" == "$CURRENT_VERSION" ]]; then
    echo "ERROR: New version is the same as the current version."
    exit 1
fi

if [[ ! "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-].+)?$ ]]; then
    echo "ERROR: Invalid version format."
    echo "Example: 1.2.3 or 1.2.3-beta1"
    exit 1
fi

TAG="v$NEW_VERSION"

read -r -p "Commit message [Bump version to $NEW_VERSION]: " COMMIT_MESSAGE
if [[ -z "$COMMIT_MESSAGE" ]]; then
    COMMIT_MESSAGE="Bump version to $NEW_VERSION"
fi

read -r -p "Tag [$TAG]: " CUSTOM_TAG
if [[ -n "$CUSTOM_TAG" ]]; then
    TAG="$CUSTOM_TAG"
fi

echo
echo "--------------------------------------"
echo "Version: $CURRENT_VERSION -> $NEW_VERSION"
echo "Commit : $COMMIT_MESSAGE"
echo "Tag    : $TAG"
echo "Branch : $BRANCH"
echo "--------------------------------------"
echo

read -r -p "Continue? [y/N]: " CONFIRM

if [[ ! "$CONFIRM" =~ ^[Yy]$ ]]; then
    echo "Cancelled."
    exit 0
fi

echo
echo "Updating version..."

sed -i -E "s/^mod_version=.*/mod_version=$NEW_VERSION/" "$VERSION_FILE"

echo
echo "Changed files:"
git diff -- "$VERSION_FILE"

echo
read -r -p "Create commit and push release? [y/N]: " CONFIRM

if [[ ! "$CONFIRM" =~ ^[Yy]$ ]]; then
    echo "Version was changed locally, but no commit/tag was created."
    exit 0
fi

echo
echo "Checking tag..."

if git rev-parse "refs/tags/$TAG" >/dev/null 2>&1; then
    echo "ERROR: Tag $TAG already exists locally."
    exit 1
fi

if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
    echo "ERROR: Tag $TAG already exists on origin."
    exit 1
fi

echo "Creating commit..."

git add "$VERSION_FILE"

if git diff --cached --quiet; then
    echo "ERROR: No staged changes."
    exit 1
fi

git commit -m "$COMMIT_MESSAGE"

echo
echo "Creating tag $TAG..."

git tag -a "$TAG" -m "$TAG"

echo
echo "Pushing branch $BRANCH..."

git push origin "$BRANCH"

echo
echo "Pushing tag $TAG..."

git push origin "$TAG"

echo
echo "======================================"
echo "Release completed successfully!"
echo "======================================"
echo "Version : $NEW_VERSION"
echo "Tag     : $TAG"
echo "Branch  : $BRANCH"
echo
