#!/usr/bin/env bash
# Hands the deployment that `./gradlew publish` just staged to the Central Portal.
#
# The OSSRH Staging API keeps an upload in a per-namespace staging repository
# that stays invisible in the Portal until this POST. Sonatype requires the call
# to come from the same IP that uploaded the files, which is why this runs as a
# step in the same job as the publish rather than from anywhere else.
#
# publishing_type:
#   user_managed (default) -- the deployment appears at
#       https://central.sonatype.com/publishing for you to release or drop.
#   automatic              -- Sonatype releases it once validation passes, with
#       no further confirmation. There is no undo for a released version.
set -euo pipefail

namespace=com.dc2f
publishing_type="${1:-user_managed}"

: "${CENTRAL_TOKEN_USERNAME:?generate a user token at https://central.sonatype.com/account}"
: "${CENTRAL_TOKEN_PASSWORD:?generate a user token at https://central.sonatype.com/account}"

# Bearer, not basic: the manual endpoints take base64("name:password") after the
# word Bearer. Built without xtrace on, so the token never reaches the log.
bearer=$(printf '%s:%s' "${CENTRAL_TOKEN_USERNAME}" "${CENTRAL_TOKEN_PASSWORD}" | base64 | tr -d '\n')

curl --fail-with-body --silent --show-error \
    --request POST \
    --header "Authorization: Bearer ${bearer}" \
    "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/${namespace}?publishing_type=${publishing_type}"

echo
echo "Handed the staged ${namespace} deployment to the Portal (${publishing_type})."
echo "Release or drop it at https://central.sonatype.com/publishing"
