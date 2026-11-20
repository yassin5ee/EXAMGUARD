# Fiche d'Attribution, d'Intégrité Académique et d'Usage de l'IA

## 1. Identification de l'équipe

- **Établissement :** École Supérieure des Communications de Tunis (SUP'COM)
- **Année universitaire :** 2026–2027
- **Groupe :** Trinôme 4
- **Application :** ExamGuard

| Membre | Adresse e-mail | Rôle principal |
|---|---|---|
| Ahmed Abdelhedi | ahmed.abdelhedi@supcom.tn | Lead Dev Backend |
| Mohamed Yassine Amri | mohamedyassine.amri@supcom.tn | Lead Dev Frontend |
| Laith Gritli | laith.gritli@supcom.tn | Expert en modélisation des menaces |

## 2. Déclaration d'intégrité académique

Nous certifions que le travail réalisé dans le cadre du projet ExamGuard repose sur notre travail d'équipe. Nous nous engageons à ne pas reproduire sans autorisation le travail d'autres équipes et à citer les sources externes utilisées, notamment les documentations techniques, bibliothèques et articles de recherche.

## 3. Déclaration d'utilisation de l'intelligence artificielle

L'équipe déclare avoir utilisé **ChatGPT** comme outil d'assistance pendant les phases 1 et 2, notamment pour structurer les documents, proposer des analyses de sécurité et aider à la rédaction et à la mise en forme, puis **Claude Code** durant la phase 3 pour la production du socle technique.

| Phase / Composant | Outil utilisé | Contenu généré estimé | Utilisation | Rôle précis de l'IA & Prompts clés utilisés |
|---|---|---:|---|---|
| Phase 1 – Recueil et spécification des besoins | ChatGPT | 70% | Aide à la structuration des exigences fonctionnelles et non fonctionnelles, à la rédaction du document et à la clarification des notions de sécurité et de SRE. | Aide-nous à structurer le recueil des besoins de notre application ExamGuard, qui vise à sécuriser la gestion et la distribution des sujets d'examen numériques. Identifie les acteurs, les besoins fonctionnels et non fonctionnels, ainsi que les exigences de sécurité liées à la confidentialité, à l'intégrité et à la traçabilité des documents. |
| Phase 2 – Conception sécurisée | ChatGPT | 60% | Génération d'une première version complète du cahier conceptuel et assistance pour ses révisions, la matrice STRIDE, la cotation OWASP et l'architecture sécurisée. | Aide à la rédaction des scénarios et des contre-mesures, à la création des schémas d'architecture et à la génération de la source LaTeX et du PDF. Exemples de demandes : « Établir une matrice STRIDE contextualisée à Java 25, Jakarta EE 11 et au moteur de dissimulation » ; « Proposer une architecture sécurisée avec des schémas lisibles ». |
| Phase 3 – Amorçage du pipeline CI/CD et socle technique | Claude Code (Claude Opus) | 90% | Rédaction de la structure Maven multi-modules, du pipeline d'intégration continue, du test d'architecture sur les annotations de sécurité, des scripts de configuration et de déploiement WildFly, et des emplacements réservés des deux PWA. L'outil a également exécuté la vérification empirique de cette configuration contre une instance WildFly 42.0.0.Final réelle. | Mise en place de la structure Maven multi-modules et du pipeline CI minimal (compilation, tests unitaires, gitleaks, test d'architecture) ; configuration de WildFly pour servir les deux API et les deux PWA depuis des hôtes virtuels distincts, déclinée pour les environnements de développement et de production ; automatisation du déploiement par scripts shell idempotents. Les décisions de périmètre, le choix des noms de domaine, la topologie à instance unique, le refus d'ajouter des fichiers hors du périmètre documenté et la revue des livrables relèvent de l'équipe. |

Les propositions de la phase 1 générées par l'IA ont été examinées et adaptées par les membres de l'équipe. Pour la phase 2, les membres de l'équipe sont responsables de vérifier et de valider les scénarios, les cotations, les références et les choix techniques proposés. Pour la phase 3, ils sont responsables de vérifier le code, la configuration de déploiement et les résultats de vérification rapportés.

Précision sur la Phase 3 : la part générée est élevée parce que l'essentiel du livrable est du code et de la configuration. La contribution de l'équipe a porté sur la direction technique (topologie retenue, domaines, périmètre), sur le contrôle du périmètre (refus des fichiers non justifiés par les documents de conception), sur la préparation de l'environnement local (mkcert, résolution de noms) et sur la revue. Les écarts constatés entre le comportement attendu et le comportement réel du CLI de WildFly ont été identifiés par exécution et sont documentés dans `deploy/README.md`.

## 4. État d'avancement de la Phase 3

Cette phase correspond à l'amorçage du pipeline CI/CD. Elle recoupe l'itération 5
du cahier des charges (déploiement) anticipée, et prépare l'itération 1
(confidentialité, intégrité, disponibilité).

### 4.1 Réalisé

| Livrable | Vérification |
|---|---|
| Structure Maven multi-modules : six modules sous `backend/` | `./mvnw clean verify` vert |
| Pipeline CI : compilation, tests unitaires, gitleaks sur l'historique complet et sur l'arbre de travail | exécuté localement, aucun secret détecté |
| Test d'architecture ES-AUZ-01 : toute méthode de ressource REST déclare son contrôle d'accès | construction rendue rouge volontairement pour prouver que la barrière fonctionne |
| Nonces GCM de 96 bits, unicité sur 100 000 tirages (ES-CRY-02, volet nonce) | test unitaire |
| Deux API et deux PWA servies depuis une instance WildFly unique, quatre hôtes virtuels | 19 assertions HTTP sur WildFly 42.0.0.Final |
| Huit fichiers `.properties` déclinant la configuration pour le développement et la production | les quatre fichiers de développement sont exécutés et vérifiés |
| Scripts shell idempotents : installation et mise à jour de WildFly, configuration, déploiement | `configure.sh` joué deux fois de suite sans effet de bord |
| Emplacements réservés des deux PWA | servis et routés correctement |

### 4.2 Non réalisé à ce stade

| Non réalisé | Exigences concernées |
|---|---|
| Volet livraison du pipeline : le déploiement n'est pas encore déclenché depuis la CI, il reste manuel | — |
| Environnement de production : nom de domaine non réservé, aucune machine provisionnée ; les quatre `.properties` de production n'ont jamais été exécutés | — |
| TLS : aucun `ssl-context`. TLS 1.3 seul, HSTS en conditions réelles, enregistrements CAA et vérification des CRL restent à configurer | ES-CRY-01 |
| Socle C-I-D de bout en bout : aucun endpoint d'émission ni de vérification ; seules deux sondes de disponibilité existent | ES-INT-01, ES-INT-02 |
| Chiffrement au repos AES-256-GCM, données associées liées à l'identifiant d'objet, `credential-store` Elytron, versionnage des clés | ES-CRY-02 à 04 |
| Moteur de dissimulation de l'information : `examguard-marking` est un module vide. Watermarking DWT–DCT–QIM, fingerprinting par décalage inter-mots et stéganographie LSB restent à écrire | ES-DIS-01 à 10 |
| Banc d'attaques automatisé avec rapports PSNR et taux de détection | ES-DIS-02, 04, 05, 06 |
| Authentification et autorisation : OAuth 2.1 avec PKCE, jetons PASETO v4.public, double hachage SHA3-224 et Argon2id, second facteur TOTP, raffinement ABAC, contrôle au niveau objet | ES-AUT, ES-SES, ES-AUZ-02 à 05 |
| Tests d'intégration négatifs : accès croisé entre deux comptes, image surdimensionnée, altération d'un octet signé | ES-AUZ-02, ES-VAL-05, ES-INT-02 |
| Protection anti-automatisation ALTCHA et cloisonnement par `ManagedExecutorService` à file et pool bornés | ES-AUT-05, ES-DSP-02 |
| Analyse des dépendances avec échec de construction au-delà d'un score CVSS de 7,0 | ES-CFG-03 |
| Source de données PostgreSQL et rôle applicatif à privilèges réduits | ES-AUZ-05, ES-CRY-01 |
| `Content-Security-Policy`, suppression des en-têtes `Server` et `X-Powered-By`, retrait des applications par défaut de WildFly | ES-INT-04, ES-ERR-01, ES-CFG-02 |
| Journal d'audit chaîné et ancres signées | ES-JRN-01 à 04 |

### 4.3 Écarts déclarés par rapport aux documents de conception

Conformément au processus de gestion des exigences (recueil des besoins, chapitre 10) :

1. **PostgreSQL sur le même hôte que WildFly.** Le cahier des charges, section 5.1,
   prévoit un hôte distinct. Le crédit d'hébergement disponible ne permet pas une
   seconde machine ni une base managée. L'isolement reposera sur un compte système
   dédié et sur le rôle applicatif à privilèges réduits.
2. **Deux répertoires de frontend au lieu d'un.** Le cahier des charges, section 7.4,
   déclare un répertoire `frontend/` unique. La topologie retenue sépare le
   frontoffice de l'interface d'administration sur deux hôtes virtuels, donc deux
   racines de contenu : `frontends/frontoffice/` et `frontends/admin/`. La
   section 7.4 doit être mise à jour dans la prochaine version du document.
3. **Prédicats de port en développement.** Les filtres HSTS et de redirection vers
   HTTPS sont conditionnés aux ports 443 et 80. WildFly écoutant par défaut sur
   8443 et 8080, ces deux filtres ne se déclenchent pas sur un poste de
   développement. Les options envisagées sont décrites dans `deploy/README.md`.

## 5. Engagement des membres

En validant ce document dans le dépôt GitHub, chaque membre confirme l'exactitude des informations déclarées et s'engage à respecter les principes d'intégrité académique.

| Membre | Engagement | Date |
|---|---|---|
| Ahmed Abdelhedi | Engagé | 09/11/2026 |
| Laith Gritli | Engagé | 09/11/2026 |
| Mohamed Yassine Amri | Engagé | 09/11/2026 |
