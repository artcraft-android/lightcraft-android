#!/usr/bin/env bash
# Builds librclone.so (rclone's C library, https://rclone.org/) for the cloud library:
#   - the Android ABIs, into android/app/src/main/jniLibs/<abi>/ (packaged by Gradle as it is)
#   - the host (linux/amd64), into librclone-host/ (for `cargo test` of lightcraft-cloud-client)
# Both outputs are gitignored. Needs Go (>= the version rclone's go.mod asks for; the build uses
# ~/.local/go when GO is not set) and, for Android, the NDK ($ANDROID_NDK_HOME, else
# $ANDROID_HOME/ndk/<newest>).
#
#   scripts/build-librclone.sh                 # arm64-v8a armeabi-v7a + host
#   scripts/build-librclone.sh host            # the host library only
#   scripts/build-librclone.sh arm64-v8a x86_64
#   LIBRCLONE_FULL=1 scripts/build-librclone.sh   # every rclone backend (about 3x the size)
#
# The library is rclone's own librclone C API (RcloneInitialize / RcloneRPC / RcloneFreeString /
# RcloneFinalize) built from a main package of our own: same exports, but only the backends a photo
# library needs (local, drive, onedrive, dropbox, s3, webdav, sftp, ftp, b2, box, pcloud, smb,
# http plus the alias and crypt wrappers) and no FUSE mount or plugin loader.
set -euo pipefail

RCLONE_VERSION="${RCLONE_VERSION:-v1.75.2}"
MIN_SDK="${MIN_SDK:-29}" # = minApi in android/app/build.gradle.kts
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jni="$here/android/app/src/main/jniLibs"
host_out="$here/librclone-host"

export PATH="${GO_ROOT:-$HOME/.local/go}/bin:$PATH"
export GOPATH="${GOPATH:-$HOME/.cache/go}" GOCACHE="${GOCACHE:-$HOME/.cache/go-build}" GOMODCACHE="${GOMODCACHE:-$HOME/.cache/go/pkg/mod}"
export GOTOOLCHAIN=local GOFLAGS=-mod=mod CGO_ENABLED=1
command -v go >/dev/null || { echo "go not found (install it in ~/.local/go or set GO_ROOT)" >&2; exit 1; }

targets=("$@")
[ ${#targets[@]} -gt 0 ] || targets=(arm64-v8a armeabi-v7a host)

work="${LIBRCLONE_WORK:-$HOME/.cache/librclone-src}"
mkdir -p "$work"
cd "$work"
if [ ! -f go.mod ]; then printf 'module lcbuild\n\ngo 1.25\n' > go.mod; fi
go get "github.com/rclone/rclone@$RCLONE_VERSION" >/dev/null

if [ -n "${LIBRCLONE_FULL:-}" ]; then
  backends='_ "github.com/rclone/rclone/backend/all"'
else
  backends=""
  for b in alias crypt local drive onedrive dropbox s3 webdav sftp ftp b2 box pcloud smb http; do
    backends+=$'\n\t'"_ \"github.com/rclone/rclone/backend/$b\""
  done
fi
mkdir -p main
cat > main/librclone.go <<GO
// Command librclone: rclone's librclone C API (github.com/rclone/rclone/librclone) with a chosen set of backends.
package main

/*
#include <stdlib.h>

struct RcloneRPCResult {
	char*	Output;
	int	Status;
};
*/
import "C"

import (
	"unsafe"

	"github.com/rclone/rclone/librclone/librclone"

	$backends
	_ "github.com/rclone/rclone/fs/operations"
	_ "github.com/rclone/rclone/fs/sync"
)

//export RcloneInitialize
func RcloneInitialize() { librclone.Initialize() }

//export RcloneFinalize
func RcloneFinalize() { librclone.Finalize() }

type RcloneRPCResult struct {
	Output *C.char
	Status C.int
}

//export RcloneRPC
func RcloneRPC(method *C.char, input *C.char) (result C.struct_RcloneRPCResult) {
	output, status := librclone.RPC(C.GoString(method), C.GoString(input))
	result.Output = C.CString(output)
	result.Status = C.int(status)
	return result
}

//export RcloneFreeString
func RcloneFreeString(str *C.char) { C.free(unsafe.Pointer(str)) }

func main() {}
GO
go mod tidy >/dev/null 2>&1 || true

ndk="${ANDROID_NDK_HOME:-}"
if [ -z "$ndk" ] && [ -d "${ANDROID_HOME:-$HOME/Android/Sdk}/ndk" ]; then
  ndk="$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/*/ | sort -V | tail -1)"
fi
llvm="${ndk%/}/toolchains/llvm/prebuilt/linux-x86_64/bin"

build() { # <out file> <extra env...>
  local out="$1"; shift
  mkdir -p "$(dirname "$out")"
  env "$@" go build -p 2 -buildmode=c-shared -trimpath -ldflags "-s -w -extldflags=-Wl,-z,max-page-size=16384 -buildid=" -o "$out" ./main
  rm -f "${out%.so}.h"
  ls -l "$out" | awk '{printf "%s  %.1f MB\n", $9, $5/1048576}'
}

for t in "${targets[@]}"; do
  case "$t" in
    host)        build "$host_out/librclone.so" GOOS=linux GOARCH=amd64 ;;
    arm64-v8a)   build "$jni/arm64-v8a/librclone.so" GOOS=android GOARCH=arm64 CC="$llvm/aarch64-linux-android$MIN_SDK-clang" ;;
    armeabi-v7a) build "$jni/armeabi-v7a/librclone.so" GOOS=android GOARCH=arm GOARM=7 CC="$llvm/armv7a-linux-androideabi$MIN_SDK-clang" ;;
    x86_64)      build "$jni/x86_64/librclone.so" GOOS=android GOARCH=amd64 CC="$llvm/x86_64-linux-android$MIN_SDK-clang" ;;
    *) echo "unknown target $t (host|arm64-v8a|armeabi-v7a|x86_64)" >&2; exit 1 ;;
  esac
done
