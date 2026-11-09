#!/usr/bin/env bash
#
# Re-encrypt a plaintext secret into the .sops.json beside it — the counterpart
# of _tools/postdeploy.sh, which decrypts.
#
#   _tools/postdeploy.sh                                     # decrypt
#   $EDITOR _tools/secrets/build_secrets.gradle.kts          # change it
#   _tools/secrets-encrypt.sh _tools/secrets/build_secrets.gradle.kts
#
# Encrypting needs no identity, only the public recipients, which sops reads
# from .sops.yaml. Both managed files are encrypted with sops' binary store —
# they are a Gradle script and a text note, not structured data — so the type
# flags are spelled out rather than left to the file extension.

set -euo pipefail

if test $# -ne 1 ; then
    echo "usage: $0 <plaintext file under _tools/secrets/>" >&2
    exit 64
fi

# Absolute first, then repo-relative: .sops.yaml matches `^_tools/secrets/`, so
# an absolute path would silently match no creation rule and sops would refuse
# for want of recipients.
plaintext=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
cd "$(dirname "$0")/.."
plaintext=${plaintext#"$PWD"/}

case "$plaintext" in
    _tools/secrets/*) ;;
    *) echo "$1 is not under _tools/secrets/" >&2 ; exit 64 ;;
esac

encrypted="$plaintext.sops.json"

echo "encrypting $plaintext -> $encrypted"
sops --encrypt --input-type binary --output-type json \
    --output "$encrypted" "$plaintext"
