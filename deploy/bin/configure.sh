#!/usr/bin/env bash
#
# Applique la configuration WildFly d'ExamGuard pour un environnement donne.
#
#   ./deploy/bin/configure.sh dev
#   ./deploy/bin/configure.sh prod
#
# Les quatre hotes virtuels vivent sur une seule instance WildFly, derriere un seul
# port 443 ; Undertow route sur l'en-tete Host via l'attribut alias.
#
#   examguard.<domaine>        default-host   PWA frontoffice   (statique)
#   admin.examguard.<domaine>  admin-host     PWA admin         (statique)
#   api.examguard.<domaine>    api-host       api-1.0.war
#   iam.examguard.<domaine>    iam-host       iam-1.0.war
#
# L'ordre d'application n'est pas negociable : 10-default-host.cli definit les
# filtres partages dont les deux autres scripts dependent.
#
# Idempotent : relancable sans effet de bord.

set -euo pipefail

usage() { echo "usage: $(basename "$0") <dev|prod>" >&2; exit 2; }
[[ $# -eq 1 ]] || usage
ENV="$1"
[[ "$ENV" == "dev" || "$ENV" == "prod" ]] || usage

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLI_DIR="$REPO_ROOT/deploy/wildfly/cli"
PROP_DIR="$REPO_ROOT/deploy/wildfly/properties"

: "${JBOSS_HOME:?JBOSS_HOME doit pointer sur le repertoire WildFly}"
JBOSS_CLI="$JBOSS_HOME/bin/jboss-cli.sh"
[[ -x "$JBOSS_CLI" ]] || { echo "introuvable ou non executable : $JBOSS_CLI" >&2; exit 1; }

# Controleur de gestion.
#
# Defaut en remote+http sur 9990 : c'est la seule interface de gestion active sur
# un WildFly fraichement installe. Le port 9993 existe comme socket-binding
# (management-https) mais n'est pas servi tant qu'un ssl-context ne lui est pas
# associe. Securiser l'interface de gestion puis basculer sur 9993 fait partie du
# durcissement reste a faire (voir README, section « Hors perimetre a ce stade »).
#
# Dans les deux cas l'ecoute reste sur la boucle locale (ES-CFG-02) : ce script
# s'execute sur l'hote, ou au bout d'un tunnel SSH, jamais contre un port expose.
CONTROLLER="${CONTROLLER:-remote+http://127.0.0.1:9990}"

# EXAMGUARD_HOME n'est utilise qu'en dev : les chemins des PWA pointent sur le
# depot, qui n'est pas au meme endroit chez les trois membres de l'equipe.
export EXAMGUARD_HOME="${EXAMGUARD_HOME:-$REPO_ROOT}"

RENDERED="$(mktemp -d)"
trap 'rm -rf "$RENDERED"' EXIT

# Rend un .properties en substituant les variables d'environnement.
# En prod les fichiers n'en contiennent aucune : la substitution est alors neutre.
render() {
  local src="$1" dst="$RENDERED/$(basename "$1")"
  if command -v envsubst >/dev/null 2>&1; then
    envsubst < "$src" > "$dst"
  else
    # Repli sans gettext : ne substitue que ${EXAMGUARD_HOME}.
    sed "s|\${EXAMGUARD_HOME}|${EXAMGUARD_HOME}|g" "$src" > "$dst"
  fi
  printf '%s' "$dst"
}

run_cli() {
  local script="$1" props="$2"
  echo "==> $(basename "$script")  <-  $(basename "$props")"
  "$JBOSS_CLI" --connect --controller="$CONTROLLER" \
               --file="$script" --properties="$(render "$props")"
}

echo "### Configuration WildFly ExamGuard -- environnement : $ENV"
echo "### JBOSS_HOME=$JBOSS_HOME"
echo "### controleur=$CONTROLLER"
[[ "$ENV" == "dev" ]] && echo "### EXAMGUARD_HOME=$EXAMGUARD_HOME"

# 1. Hote par defaut + definition des filtres partages. Toujours en premier.
run_cli "$CLI_DIR/10-default-host.cli" "$PROP_DIR/default-host-$ENV.properties"

# 2. Hote statique d'administration.
run_cli "$CLI_DIR/20-add-host.cli"     "$PROP_DIR/admin-host-$ENV.properties"

# 3. Hotes applicatifs : un seul script, joue une fois par deployable.
run_cli "$CLI_DIR/30-add-api.cli"      "$PROP_DIR/api-$ENV.properties"
run_cli "$CLI_DIR/30-add-api.cli"      "$PROP_DIR/iam-$ENV.properties"

# Un seul reload, a la fin, plutot qu'un par script.
echo "==> reload"
"$JBOSS_CLI" --connect --controller="$CONTROLLER" --command=":reload"

echo "### Configuration appliquee."
