#!/usr/bin/env bash
#
# Emit `export` lines for the credentials a release needs, decrypted from sops.
# This is what makes a release independent of GitHub: with the age identity and
# a checkout, the whole publish path runs from a laptop.
#
#   eval "$(_tools/secrets-env.sh)"
#   ./gradlew publish -Pversion=0.3.1
#   _tools/central-publish.sh
#
# CI does not use this. The publish job reads SIGNING_KEY and the Portal token
# from repository secrets, and the signing key is encrypted to the owner alone
# (see .sops.yaml), so the CI identity could not decrypt it anyway -- which is
# the point: a leaked CI key must not be able to sign a release as dc2f.
#
# `eval` is the awkward part and it is unavoidable: an environment variable
# cannot be set in the parent shell from a child process. The alternative,
# writing the values to a file, is strictly worse -- it leaves the signing key
# in plaintext on disk after the release.

set -euo pipefail

# Refuse to write to a terminal, so a stray run cannot paint the signing key
# into the screen and the scrollback.
if test -t 1 ; then
    echo "$0 writes secrets to stdout. Use: eval \"\$($0)\"" >&2
    exit 64
fi

cd "$(dirname "$0")/.."

# sops looks for the age key file under os.UserConfigDir(), which on macOS is
# ~/Library/Application Support/sops/age/keys.txt rather than the ~/.config path
# the age documentation uses. Same fallback as _tools/postdeploy.sh.
if test -z "${SOPS_AGE_KEY:-}" && test -z "${SOPS_AGE_KEY_FILE:-}" \
        && test -e "${XDG_CONFIG_HOME:-$HOME/.config}/sops/age/keys.txt" ; then
    export SOPS_AGE_KEY_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/sops/age/keys.txt"
fi

decrypt() {
    sops --decrypt --output-type binary "_tools/secrets/$1.sops.json"
}

# Pull one `label: value` line out of one of the notes, and fail loudly when it
# is not there. Exporting an empty credential would get a 401 from Sonatype or
# an unsigned artifact, and from that end a missing label and a renamed one look
# identical -- so the check belongs here, where it can name the file.
field() {
    local note=$1 label=$2 value
    value=$(decrypt "$note" | sed -n "s/^${label}: *//p")
    if test -z "$value" ; then
        echo "no '${label}:' line in _tools/secrets/${note}.sops.json" >&2
        exit 1
    fi
    printf '%s' "$value"
}

signing_key=$(decrypt signing_key.asc)
case "$signing_key" in
    "-----BEGIN PGP PRIVATE KEY BLOCK-----"*) ;;
    *)
        echo "_tools/secrets/signing_key.asc.sops.json is not an armoured private key" >&2
        exit 1
        ;;
esac

# Each value goes into a variable of its own first, so that `set -e` sees the
# assignment fail. Calling field() inside the printf arguments instead looks
# tidier and is wrong: its `exit 1` would only leave the command-substitution
# subshell, printf would still succeed with an empty string, and the script
# would emit a blank credential and report success.
central_user=$(field central_portal_token.txt username)
central_pass=$(field central_portal_token.txt password)
signing_pass=$(field signing_key_passphrase.txt passphrase)

# printf %q quotes for the shell, including the newlines in the armoured key,
# which plain single-quoting would not survive if a value ever contained a quote.
printf 'export CENTRAL_TOKEN_USERNAME=%q\n' "$central_user"
printf 'export CENTRAL_TOKEN_PASSWORD=%q\n' "$central_pass"
printf 'export SIGNING_PASSWORD=%q\n' "$signing_pass"
printf 'export SIGNING_KEY=%q\n' "$signing_key"
