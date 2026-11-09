#!/usr/bin/env bash
#
# Materialise the sops-encrypted build secrets as a plaintext Gradle script.
#
# build.gradle.kts reads _tools/secrets/build_secrets.gradle.kts with
# `apply(from = ...)`, which needs a real file on disk, so the decrypted copy
# has to exist before the build configures. It is gitignored.
#
# Only that one file is written. _tools/secrets/signing_key_passphrase.txt is
# also under sops, but nothing automated reads it — it is a recovery note for a
# human — so CI has no reason to put it on disk. Read it on demand with
#   sops -d --output-type binary _tools/secrets/signing_key_passphrase.txt.sops.json
#
# Identities: locally yours, from the sops age key file; in CI the SOPS_AGE_KEY
# repository secret holds the private half of the CI age key. .sops.yaml lists
# both public recipients.

set -euo pipefail

cd "$(dirname "$0")/.."

# sops looks for the age key file under os.UserConfigDir(), which on macOS is
# ~/Library/Application Support/sops/age/keys.txt rather than the ~/.config path
# the age documentation and every Linux box use. Point it at ~/.config when that
# is where the identity actually is, so the same command works on both.
if test -z "${SOPS_AGE_KEY:-}" && test -z "${SOPS_AGE_KEY_FILE:-}" \
        && test -e "${XDG_CONFIG_HOME:-$HOME/.config}/sops/age/keys.txt" ; then
    export SOPS_AGE_KEY_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/sops/age/keys.txt"
fi

encrypted=_tools/secrets/build_secrets.gradle.kts.sops.json
plaintext=_tools/secrets/build_secrets.gradle.kts

echo "decrypting $encrypted -> $plaintext"
sops --decrypt --output-type binary --output "$plaintext" "$encrypted"
chmod 600 "$plaintext"
