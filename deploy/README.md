# Déploiement WildFly — ExamGuard

Configuration et automatisation du service des deux API et des deux PWA depuis
WildFly 42 (Java 25). Aucun code Java ici.

## Topologie

Les quatre hôtes virtuels vivent sur **une seule instance WildFly**, derrière un
seul port 443. Undertow route sur l'en-tête `Host` via l'attribut `alias`.

| Hôte virtuel   | Dev                     | Prod                        | Contenu                    |
|----------------|-------------------------|-----------------------------|----------------------------|
| `default-host` | `examguard.local`       | `examguard.tr3m0x.me`       | PWA frontoffice (statique) |
| `admin-host`   | `admin.examguard.local` | `admin.examguard.tr3m0x.me` | PWA admin (statique)       |
| `api-host`     | `api.examguard.local`   | `api.examguard.tr3m0x.me`   | `api-1.0.war`              |
| `iam-host`     | `iam.examguard.local`   | `iam.examguard.tr3m0x.me`   | `iam-1.0.war`              |

Un seul certificat couvre les quatre noms. Attention : un joker
`*.examguard.tr3m0x.me` ne couvre **pas** `examguard.tr3m0x.me` lui-même — il faut
soit cinq SAN explicites, soit le joker plus le nom nu.

## Ordre d'exécution — non négociable

`10-default-host.cli` est le **seul** script qui *définit* les filtres partagés
(`hsts-header`, `http-to-https`, `cache-control`, `content-type-options`,
`api-no-store`, et les six en-têtes `Access-Control-*`). Les deux autres ne font
que s'y référer. Joués avant lui sur un WildFly neuf, ils échouent. Les préfixes
numériques encodent cette dépendance.

`30-add-api.cli` est joué **deux fois** : une fois avec `api-*.properties`, une
fois avec `iam-*.properties`. Un seul script, deux hôtes.

## Utilisation

```bash
export JBOSS_HOME=/opt/wildfly

./deploy/bin/install-wildfly.sh                 # installe ou met à jour WildFly
./mvnw -B clean verify                          # produit api-1.0.war et iam-1.0.war
./deploy/bin/configure.sh dev                   # applique les 3 scripts .cli
./deploy/bin/deploy.sh dev                      # pousse les deux WAR
```

`configure.sh` et `deploy.sh` sont idempotents : le pipeline les rejoue.

### Démonstration C-I-D : émettre et vérifier un exemplaire

Sur une instance WildFly locale avec HTTPS, ajoutez `-Dexamguard.copies.demo.enabled=true`
aux options JVM au démarrage. Configurez trois comptes applicatifs de test dans le domaine
de sécurité de l'application (`add-user.sh -a` pour le domaine WildFly par défaut), avec les
rôles `ENSEIGNANT`, `RESPONSABLE_EXAMEN` et `AUDITEUR`. Les routes utilisent HTTP Basic
sur HTTPS. Les documents chiffrés et les clés de démonstration sont en mémoire : la limite
est de quatre exemplaires et un redéploiement les efface. Utilisez des PDF fictifs.

Dans Postman, choisissez **Body → binary** et l'en-tête `Content-Type: application/pdf`
pour les deux requêtes POST. Utilisez le nom de l'hôte API et le port HTTPS de votre instance.

1. Avec le compte enseignant, envoyez `POST /api/exam-copies?recipientId=amal` avec un PDF
   de 1 à 50 pages et d'au plus 20 Mio. Remplacez `amal` par le nom du compte responsable.
   La réponse `201` fournit `copyId` et `downloadUrl`.
2. Avec le compte responsable, ouvrez `downloadUrl` et enregistrez le PDF de la réponse.
   Un autre responsable doit recevoir `404` en utilisant la même URL.
3. Avec le compte auditeur, envoyez `POST /api/exam-copies/verify?copyId=<id>` avec le fichier
   téléchargé. Attendez `{"verdict":"VALID"}`. Soumettez ensuite une version modifiée du
   fichier avec le même identifiant et attendez `{"verdict":"HASH_MISMATCH"}`.

Cette démonstration vérifie l'intégrité exacte d'un PDF. Le marquage, les fenêtres de
distribution et l'authentification IAM sont des étapes ultérieures.

### Variables d'environnement

| Variable         | Défaut                          | Rôle                           |
|------------------|---------------------------------|--------------------------------|
| `JBOSS_HOME`     | *(obligatoire)*                 | racine WildFly                 |
| `CONTROLLER`     | `remote+http://127.0.0.1:9990`  | contrôleur de gestion          |
| `EXAMGUARD_HOME` | racine du dépôt                 | dev seulement : chemin des PWA |

### Pourquoi le contrôleur est en `http://127.0.0.1:9990`

Deux raisons distinctes, le port et l'hôte.

**Le port.** Sur un WildFly fraîchement installé, la seule interface de gestion
active est `management-http`, sur 9990. Le port 9993 existe comme
`socket-binding` nommé `management-https`, mais il n'est pas servi tant qu'un
`ssl-context` ne lui est pas associé. Viser 9993 avant ce durcissement échoue
donc sur connexion refusée (voir « Hors périmètre à ce stade »).

**L'hôte.** ES-CFG-02 impose que l'interface de gestion n'écoute que sur
`127.0.0.1` et que seul le port 443 soit exposé. Nous conservons l'écoute en
boucle locale et atteignons `jboss-cli` **depuis l'hôte**, ou au bout d'un tunnel
SSH. Le trafic de gestion ne quitte donc jamais la machine, ce qui rend le défaut
de TLS sur ce canal acceptable à titre transitoire.

`CONTROLLER` reste surchargeable :

```bash
CONTROLLER=remote+https://127.0.0.1:9993 ./deploy/bin/configure.sh prod
```

## Pièges du CLI WildFly — à ne pas défaire

Les scripts `.cli` de ce répertoire sont écrits d'une façon qui peut sembler
inutilement contournée. Chacun de ces choix répond à un comportement du CLI
**constaté par exécution** contre un WildFly 42.0.0.Final, non supposé. Les
reformuler « plus proprement » casse la configuration, le plus souvent en
silence.

| # | Piège | Conséquence et parade |
|---|-------|-----------------------|
| 1 | Le CLI dépouille **toutes** les contre-obliques d'un motif | Un motif écrit `(?:.+\.)?<domaine>(?::\d{1,5})?` arrive comme `(?:.+.)?<domaine>(?::d{1,5})?` : les points deviennent des jokers et le groupe de port ne reconnaît plus un port. D'où `[0-9]` et `[.]`, qui traversent le parseur intacts, et des points écrits `[.]` dans les `.properties`. |
| 2 | `--properties` n'est substitué que dans `resolve-expression` | `${ma.propriete}` écrit directement dans une opération (`:add(header-value="${ma.propriete}")`) part tel quel vers le serveur, qui échoue en `WFLYCTL0211: Cannot resolve expression`. Toute valeur issue d'un `.properties` passe donc par ``set x=`:resolve-expression(...)` ``. |
| 3 | `$maVariable` n'est pas substituée entre guillemets | D'où le motif `set escaped=\"$corsRegex\"` — la variable **contient** ses guillemets — puis un usage **sans** guillemets : `predicate=$escaped`. |
| 4 | `response-header` n'interprète pas les attributs d'échange | Un `header-value="%{i,Origin}"` renvoie la chaîne littérale. Comme `*` est par ailleurs refusé par les navigateurs dès que `Allow-Credentials` vaut `true`, **aucune valeur statique ne convient** pour `Access-Control-Allow-Origin` : l'en-tête passe par un `expression-filter` portant `set(attribute='%{o,Access-Control-Allow-Origin}', value=%{i,Origin})`, gardé par le même prédicat que les cinq autres. |
| 5 | `set` n'est pas admis dans un `batch` | C'est une commande locale du CLI, pas une opération de gestion. Tous les `set` restent en tête de fichier, hors du lot. |
| 6 | `echo` ne développe pas les variables | `$hostKey` est imprimé tel quel, guillemets compris, et un `>` non protégé provoque une erreur de syntaxe aesh. Aucun `echo` dans les `.cli` : c'est `configure.sh` qui rend compte de l'avancement. |
| 7 | `add` échoue en `Duplicate resource` au second passage | Le pipeline rejoue ces scripts. Chaque création est donc gardée par `if (outcome != success) of ...:read-resource`, avec une branche `else` qui remet à jour ce qui dépend de l'environnement via `write-attribute`, naturellement idempotent. |

Le piège n° 1 mérite d'être souligné : ses effets sont **invisibles en
production** et **bloquants en développement**. Une origine sans port
(`https://examguard.tr3m0x.me`, port 443 implicite) satisfait un motif même
dégradé ; une origine avec port explicite
(`https://api.examguard.local:8443`, cas du poste de développement) est rejetée,
et aucun en-tête CORS n'est alors renvoyé.

### Choix de configuration

- `Access-Control-Max-Age` à 600 s : à une seconde, presque chaque requête serait
  précédée de son préflight.
- HSTS avec `max-age` et `includeSubDomains`, mais **sans** `preload` : une
  soumission à hstspreload.org engagerait tout le domaine parent, hors périmètre
  du projet.
- `api-no-store` (`Cache-Control: no-store`) sur les hôtes applicatifs, exigé par
  ES-CFG-01 pour les réponses d'API sensibles. Le filtre
  `cache-control` de l'hôte par défaut vaut `private,no-cache`, ce qui autorise
  encore la mise en cache : il ne convient pas pour l'API.

## Validation

Résultats obtenus sur WildFly 42.0.0.Final, Java 25, configuration vierge :

- `configure.sh dev` joué deux fois de suite : aucune erreur, aucun
  `Duplicate resource`, et le second passage ne déclenche pas de
  `reload-required` (rien n'a changé) — idempotence vérifiée.
- `deploy.sh dev` : `api-1.0.war` et `iam-1.0.war` en statut `OK`.
- 19 assertions HTTP passées : routage des quatre hôtes virtuels, cloisonnement
  (`/api/health` → 404 sur `iam-host` et réciproquement), repli SPA, en-têtes
  `Cache-Control` et `X-Content-Type-Options`, et contrôle d'origine CORS —
  trois origines légitimes acceptées (dont une avec port), cinq rejetées
  (`examguard-local`, `examguardXlocal`, `evil.com`,
  `examguard.local.evil.com`, `http://examguard.local`).

## Décision en attente : ports du prédicat

Les prédicats des filtres `hsts-header` et `http-to-https` sont littéraux :
`equals(%p,443)` et `equals(%p,80)`. Or `standalone.xml` écoute par défaut sur
**8080 et 8443**. En développement ces deux filtres ne se déclenchent donc
jamais. Trois options :

1. lier le serveur de dev à 80/443 (`CAP_NET_BIND_SERVICE` ou `authbind`), pour
   que dev et prod partagent exactement les mêmes prédicats ;
2. élargir les prédicats (`equals(%p,443) or equals(%p,8443)`) ;
3. accepter que ces deux contrôles ne soient vérifiés qu'en production.

## Hors périmètre à ce stade

Exigences réelles, non couvertes par les trois scripts, à ajouter avec le socle
C-I-D :

- `ssl-context` Elytron en TLS 1.3 seul (ES-CRY-01)
- `Content-Security-Policy` (ES-INT-04)
- `max-post-size` et `read-timeout` sur l'`https-listener` (ES-DSP-02)
- suppression des en-têtes `Server` et `X-Powered-By` (ES-ERR-01)
- retrait des applications par défaut (ES-CFG-02)
- source de données PostgreSQL en `sslmode=verify-full` (ES-CRY-01)
- `credential-store` Elytron (ES-CRY-03)
- `ssl-context` sur l'interface de gestion, puis bascule sur 9993
- `ManagedExecutorService` à file et pool bornés (ES-DSP-02)

## Couplage à ne pas rompre

`add-api.cli` résout le module web par défaut comme
`${api.name}-${api.version}.war`. Les `<finalName>` des modules Maven
`examguard-api` et `examguard-iam` sont alignés sur ces valeurs. **Modifier
`api.name` ou `api.version` dans un `.properties` sans modifier le `pom.xml`
correspondant casse le déploiement**, et réciproquement.
