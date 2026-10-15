#!/usr/bin/env bash
# Build the Company BI image (linux/amd64) from committed source and write its release manifest.
#
# Runs on the BUILD machine (developer laptop / dedicated builder), never on the tower.
# Needs: git, docker (BuildKit), bash, sha256sum, tar.  Works in Git Bash on Windows and on Linux.
#
#   build.sh                      pure build of HEAD (refuses when the work tree is dirty)
#   build.sh --allow-dirty        build the work tree incl. uncommitted changes; version gets "-dirty"
#   build.sh --no-cache           ignore the BuildKit layer cache
#   build.sh --npm-registry URL   override the registry from core-frontend/.npmrc
#   build.sh --previous-manifest FILE
#                                 compare schema fingerprint with an earlier release manifest
#
# Output: image  companybi:<version>   and   deploy/companybi/dist/<version>/companybi_<version>.manifest
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
DIST_DIR="$ROOT/deploy/companybi/dist"

ALLOW_DIRTY=0; NO_CACHE=""; NPM_REGISTRY=""; PREV_MANIFEST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --allow-dirty) ALLOW_DIRTY=1 ;;
    --no-cache) NO_CACHE="--no-cache" ;;
    --npm-registry) NPM_REGISTRY="${2:?}"; shift ;;
    --previous-manifest) PREV_MANIFEST="${2:?}"; shift ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

die() { echo "build: ERROR: $*" >&2; exit 1; }
command -v docker >/dev/null || die "docker not found"
docker info >/dev/null 2>&1 || die "docker daemon is not reachable"
cd "$ROOT"
git rev-parse --git-dir >/dev/null 2>&1 || die "not inside a git work tree: $ROOT"

GIT_SHA="$(git rev-parse HEAD)"
GIT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
# Anything tracked-but-modified or untracked-but-not-ignored makes the build "dirty".
# deploy/companybi/dist is build output and is ignored via .gitignore.
if [ -n "$(git status --porcelain --untracked-files=normal)" ]; then DIRTY=dirty; else DIRTY=clean; fi

if [ "$DIRTY" = dirty ] && [ "$ALLOW_DIRTY" -ne 1 ]; then
  echo "build: the work tree has uncommitted changes:" >&2
  git status --short >&2
  die "refusing to build a release from a dirty tree. Commit first, or pass --allow-dirty for a local test image."
fi

DE_VERSION="$(sed -n 's|.*<dataease.version>\(.*\)</dataease.version>.*|\1|p' pom.xml | head -1)"
[ -n "$DE_VERSION" ] || die "cannot read <dataease.version> from pom.xml"
COMMIT_DATE="$(TZ=UTC git log -1 --format=%cd --date=format-local:%Y%m%d)"
VERSION="${DE_VERSION}-${COMMIT_DATE}-${GIT_SHA:0:8}"
[ "$DIRTY" = dirty ] && VERSION="${VERSION}-dirty"
IMAGE="companybi:${VERSION}"
BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "build: version   = $VERSION"
echo "build: git       = $GIT_SHA ($GIT_BRANCH, $DIRTY)"
echo "build: image     = $IMAGE (linux/amd64)"

# --- build context: a tar stream, so only intended files can ever reach the image ---
ctx_tar() {
  if [ "$DIRTY" = clean ]; then
    git archive --format=tar HEAD
  else
    # tracked + untracked-not-ignored files exactly as they are on disk
    git ls-files -z --cached --others --exclude-standard | tar --null -T - -cf -
  fi
}

BUILD_ARGS=(
  --platform linux/amd64
  # plain single-platform image: no provenance/SBOM attestation manifests, so `docker save` / `docker load`
  # yields exactly one image on any Docker version
  --provenance=false --sbom=false
  -f deploy/companybi/Dockerfile
  -t "$IMAGE"
  --build-arg "COMPANYBI_VERSION=$VERSION"
  --build-arg "GIT_SHA=$GIT_SHA"
  --build-arg "GIT_BRANCH=$GIT_BRANCH"
  --build-arg "GIT_DIRTY=$DIRTY"
  --build-arg "BUILD_TIME=$BUILD_TIME"
  --build-arg "DATAEASE_VERSION=$DE_VERSION"
)
[ -n "$NPM_REGISTRY" ] && BUILD_ARGS+=(--build-arg "NPM_REGISTRY=$NPM_REGISTRY")
[ -n "$NO_CACHE" ] && BUILD_ARGS+=("$NO_CACHE")

ctx_tar | docker build "${BUILD_ARGS[@]}" -

# --- verify the result and collect facts ---------------------------------
IMAGE_ID="$(docker image inspect --format '{{.Id}}' "$IMAGE")"
ARCH="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$IMAGE")"
[ "$ARCH" = "linux/amd64" ] || die "image architecture is $ARCH, expected linux/amd64"
SIZE_BYTES="$(docker image inspect --format '{{.Size}}' "$IMAGE")"

OUT="$DIST_DIR/$VERSION"
mkdir -p "$OUT"
read_in_image() { docker run --rm --entrypoint /bin/cat "$IMAGE" "/opt/apps/release/$1"; }
read_in_image SCHEMA        > "$OUT/SCHEMA"
read_in_image RELEASE       > "$OUT/RELEASE"
read_in_image JAR.SHA256    > "$OUT/JAR.SHA256"
read_in_image CONTENT-CHECK > "$OUT/CONTENT-CHECK"
read_in_image COMPAT        > "$OUT/COMPAT"

kv() { sed -n "s/^$2=//p" "$1" | head -1; }
JAR_SHA="$(kv "$OUT/RELEASE" jar_sha256)"
FP="$(kv "$OUT/RELEASE" schema_fingerprint)"
[ -n "$JAR_SHA" ] && [ -n "$FP" ] || die "release metadata inside the image is incomplete"
[ "$(kv "$OUT/RELEASE" version)" = "$VERSION" ] || die "RELEASE inside the image disagrees with the requested version"

SCHEMA_CHANGED=unknown
if [ -n "$PREV_MANIFEST" ]; then
  [ -f "$PREV_MANIFEST" ] || die "previous manifest not found: $PREV_MANIFEST"
  if [ "$(kv "$PREV_MANIFEST" schema_fingerprint)" = "$FP" ]; then SCHEMA_CHANGED=no; else SCHEMA_CHANGED=yes; fi
fi

MANIFEST="$OUT/companybi_${VERSION}.manifest"
{
  echo "# Company BI release manifest (generated by build.sh; do not edit)"
  echo "version=$VERSION"
  echo "image=$IMAGE"
  echo "image_id=$IMAGE_ID"
  echo "image_platform=$ARCH"
  echo "image_size_bytes=$SIZE_BYTES"
  echo "repo_digest=none (not pushed to a registry; image_id is the identity)"
  echo "git_sha=$GIT_SHA"
  echo "git_branch=$GIT_BRANCH"
  echo "git_dirty=$DIRTY"
  echo "build_time=$BUILD_TIME"
  echo "dataease_version=$DE_VERSION"
  echo "jar_sha256=$JAR_SHA"
  echo "schema_fingerprint=$FP"
  echo "schema_changed_vs_previous=$SCHEMA_CHANGED"
  [ -n "$PREV_MANIFEST" ] && echo "previous_version=$(kv "$PREV_MANIFEST" version)" || true
  echo "schema_destructive_keywords=$(grep -c '^risk\.' "$OUT/SCHEMA" || true)"
  sed 's/^/content_/' "$OUT/CONTENT-CHECK"
} > "$MANIFEST"

echo
echo "build: OK"
echo "build: image_id   $IMAGE_ID"
echo "build: jar_sha256 $JAR_SHA"
echo "build: schema     $FP"
echo "build: manifest   $MANIFEST"
if [ "$DIRTY" = dirty ]; then
  echo "build: WARNING    this is NOT a pure Git version (uncommitted changes baked in); do not use it as a production release."
fi
echo "build: next: deploy/companybi/scripts/export-image.sh $VERSION"
