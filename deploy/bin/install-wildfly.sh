#!/usr/bin/env bash
#
# Installe ou met a jour WildFly.
#
#   ./deploy/bin/install-wildfly.sh                 # version epinglee ci-dessous
#   ./deploy/bin/install-wildfly.sh --version 42.0.0.Final
#   PREFIX=$HOME/opt ./deploy/bin/install-wildfly.sh --no-service   # poste de dev
#   ./deploy/bin/install-wildfly.sh --archive /chemin/wildfly-42.0.0.Final.tar.gz
#
# Strategie de mise a jour : chaque version est installee dans son propre
# repertoire, et PREFIX/wildfly est un lien symbolique vers la version active.
# Mettre a jour revient donc a deplacer un lien, et revenir en arriere a le
# remettre -- plutot qu'a ecraser une installation en place. Le repertoire de
# configuration (standalone/) de l'ancienne version reste disponible pour report.
#
# Verification d'integrite : signature OpenPGP du projet WildFly, et condensat
# SHA-256 epingle ci-dessous. Le projet publie aussi un .sha1, non utilise ici :
# ES-CRY-05 proscrit SHA-1.

set -euo pipefail

WILDFLY_VERSION="42.0.0.Final"
# sha256 de wildfly-42.0.0.Final.tar.gz, releve a l'installation de reference.
WILDFLY_SHA256="6c2e50b8f3e2c0c3ad8f5241e18276f12bfd3e6e37b750e3fe1d6ce1dd2800ce"
PREFIX="${PREFIX:-/opt}"
SERVICE=1
WILDFLY_USER="${WILDFLY_USER:-wildfly}"
ARCHIVE_PATH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) WILDFLY_VERSION="$2"; WILDFLY_SHA256=""; shift 2 ;;
    --archive) ARCHIVE_PATH="$2"; shift 2 ;;
    --no-service) SERVICE=0; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "option inconnue : $1" >&2; exit 2 ;;
  esac
done

ARCHIVE="wildfly-${WILDFLY_VERSION}.tar.gz"
BASE_URL="https://github.com/wildfly/wildfly/releases/download/${WILDFLY_VERSION}"
TARGET="${PREFIX}/wildfly-${WILDFLY_VERSION}"
LINK="${PREFIX}/wildfly"

if [[ -d "$TARGET" ]]; then
  echo "### ${TARGET} existe deja."
else
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT

  if [[ -n "$ARCHIVE_PATH" ]]; then
    [[ -f "$ARCHIVE_PATH" ]] || { echo "archive introuvable : $ARCHIVE_PATH" >&2; exit 1; }
    echo "### Archive locale : $ARCHIVE_PATH"
    cp "$ARCHIVE_PATH" "$WORK/$ARCHIVE"
    [[ -f "${ARCHIVE_PATH}.asc" ]] && cp "${ARCHIVE_PATH}.asc" "$WORK/$ARCHIVE.asc"
  else
    echo "### Telechargement de WildFly ${WILDFLY_VERSION}"
    # --speed-limit/--speed-time coupent une connexion qui s'eteint sans fermer,
    # et -C - permet la reprise : sans cela curl peut rendre 0 sur un fichier
    # tronque, et l'archive ne se verifie plus.
    for essai in 1 2 3 4 5; do
      curl -fsSL -C - --retry 5 --retry-delay 5 \
           --speed-limit 1024 --speed-time 60 \
           -o "$WORK/$ARCHIVE" "$BASE_URL/$ARCHIVE" && break
      echo "### reprise du telechargement (tentative $essai)"
    done
    curl -fsSL --retry 3 -o "$WORK/$ARCHIVE.asc" "$BASE_URL/$ARCHIVE.asc" || true
  fi

  if [[ -n "$WILDFLY_SHA256" ]]; then
    echo "### Verification SHA-256"
    echo "${WILDFLY_SHA256}  $WORK/$ARCHIVE" | sha256sum -c - \
      || { echo "condensat invalide, installation interrompue" >&2; exit 1; }
  else
    echo "### Pas de SHA-256 epingle pour cette version."
    echo "### Condensat de ce telechargement : $(sha256sum "$WORK/$ARCHIVE" | cut -d" " -f1)"
    echo "### A epingler dans ce script avant usage en production."
  fi

  if [[ -f "$WORK/$ARCHIVE.asc" ]] && command -v gpg >/dev/null 2>&1; then
    echo "### Verification de la signature OpenPGP"
    gpg --verify "$WORK/$ARCHIVE.asc" "$WORK/$ARCHIVE" \
      || echo "### AVERTISSEMENT : signature non verifiee (cle du projet absente du trousseau ?)"
  fi

  echo "### Extraction vers ${TARGET}"
  mkdir -p "$PREFIX"
  tar -xzf "$WORK/$ARCHIVE" -C "$PREFIX"
fi

# Lien de version active : la bascule est atomique, le retour arriere trivial.
PREVIOUS="$(readlink "$LINK" 2>/dev/null || true)"
ln -sfn "$TARGET" "$LINK"
echo "### ${LINK} -> ${TARGET}"
[[ -n "$PREVIOUS" && "$PREVIOUS" != "$TARGET" ]] && \
  echo "### Version precedente conservee : ${PREVIOUS}  (retour arriere : ln -sfn ${PREVIOUS} ${LINK})"

if [[ "$SERVICE" -eq 0 ]]; then
  cat <<EOF

### Installation de developpement terminee.
    export JBOSS_HOME="${LINK}"
    "\$JBOSS_HOME/bin/standalone.sh" -c standalone.xml

### Aucun compte de gestion n'est requis : une connexion jboss-cli venant de la
### machine locale est authentifiee par le mecanisme JBOSS-LOCAL-USER. add-user.sh
### ne sert que pour un acces distant ou la console web.
EOF
  exit 0
fi

[[ "$(id -u)" -eq 0 ]] || { echo "le mode service requiert les droits root (ou --no-service)" >&2; exit 1; }

# Compte de service non privilegie, sans interpreteur de connexion (ES-CFG-02).
if ! id -u "$WILDFLY_USER" >/dev/null 2>&1; then
  echo "### Creation du compte systeme ${WILDFLY_USER}"
  useradd --system --no-create-home --shell /usr/sbin/nologin "$WILDFLY_USER"
fi
chown -R "$WILDFLY_USER":"$WILDFLY_USER" "$TARGET"

# Racines de contenu statique servies par Undertow, hors du repertoire WildFly.
install -d -o "$WILDFLY_USER" -g "$WILDFLY_USER" /var/opt/wildfly/www/root
install -d -o "$WILDFLY_USER" -g "$WILDFLY_USER" /var/opt/wildfly/www/admin

cat > /etc/systemd/system/wildfly.service <<EOF
[Unit]
Description=WildFly ${WILDFLY_VERSION} (ExamGuard)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${WILDFLY_USER}
Group=${WILDFLY_USER}
Environment=JBOSS_HOME=${LINK}
# L'interface de gestion n'ecoute que sur la boucle locale : le pipeline atteint
# jboss-cli depuis l'hote ou par tunnel SSH, jamais par un port 9993 expose.
ExecStart=${LINK}/bin/standalone.sh -c standalone.xml -b 0.0.0.0 -bmanagement 127.0.0.1
Restart=on-failure
RestartSec=10
# Durcissement systemd
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=full
ProtectHome=yes
ReadWritePaths=${TARGET}/standalone /var/opt/wildfly

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
echo "### Service installe. Demarrage :  systemctl enable --now wildfly"
