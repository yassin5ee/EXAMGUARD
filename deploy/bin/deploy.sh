#!/usr/bin/env bash
#
# Deploie les deux WAR sur WildFly.
#
#   ./deploy/bin/deploy.sh dev
#   ./deploy/bin/deploy.sh prod
#
# Les noms de fichiers ne sont pas libres : add-api.cli resout le module web par
# defaut de chaque hote comme ${api.name}-${api.version}.war, d'ou api-1.0.war et
# iam-1.0.war. Le <finalName> des modules Maven correspondants est aligne dessus.

set -euo pipefail

usage() { echo "usage: $(basename "$0") <dev|prod>" >&2; exit 2; }
[[ $# -eq 1 ]] || usage
ENV="$1"
[[ "$ENV" == "dev" || "$ENV" == "prod" ]] || usage

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

: "${JBOSS_HOME:?JBOSS_HOME doit pointer sur le repertoire WildFly}"
JBOSS_CLI="$JBOSS_HOME/bin/jboss-cli.sh"
CONTROLLER="${CONTROLLER:-remote+http://127.0.0.1:9990}"

API_WAR="$REPO_ROOT/backend/examguard-api/target/api-1.0.war"
IAM_WAR="$REPO_ROOT/backend/examguard-iam/target/iam-1.0.war"

for war in "$API_WAR" "$IAM_WAR"; do
  [[ -f "$war" ]] || {
    echo "absent : $war" >&2
    echo "construire d'abord :  ./mvnw -B clean verify" >&2
    exit 1
  }
done

echo "### Deploiement ExamGuard -- environnement : $ENV"
for war in "$API_WAR" "$IAM_WAR"; do
  echo "==> $(basename "$war")"
  # --force remplace la version deja deployee : le pipeline rejoue ce script.
  "$JBOSS_CLI" --connect --controller="$CONTROLLER" \
               --command="deploy $war --force"
done

echo "==> deploiements actifs"
"$JBOSS_CLI" --connect --controller="$CONTROLLER" --command="deployment-info"
