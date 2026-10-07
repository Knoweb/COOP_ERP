# Demo server, small size: 2 vCPU / 4 GB

This guide puts the COOP ERP demo (demo data only) on one server with **2 vCPU and 4 GB of memory**. It is complete on its own: follow it from top to bottom. The bigger size has its own guide, [SETUP-4vCPU-8GB.md](SETUP-4vCPU-8GB.md).

**What this size carries.** The whole demo runs well for a group working through the storyline. When many testers sign in or print PDFs at the same moment (a class of 30 pressing "Sign in" together), expect a slow minute rather than an error: two cores are shared by everything, and the server leans on its swap for a moment. For about 80 testers working at once, take the medium size.

The steps use DigitalOcean. Another provider works the same way; see [Other providers](#other-providers-eg-contabo).

## What you need first

- A **DigitalOcean account** with a payment method.
- An **SSH key** on your computer (`ssh-keygen -t ed25519` if you have none); you paste its public half (`~/.ssh/id_ed25519.pub`) into DigitalOcean.
- **Read access to the repository** `Knoweb/COOP_ERP` on GitHub, and to its container images (see step 3).
- Optional: an e-mail address for Let's Encrypt (it writes there before a certificate expires; the renewal itself is automatic).

## 1. Create the Droplet

In DigitalOcean: **Create → Droplets**.

| Setting | Choose |
|---|---|
| Region | **Bangalore (BLR1)** or **Singapore (SGP1)**, the closest to Sri Lanka |
| Image | **Ubuntu 24.04 (LTS) x64** |
| Size | Basic, Regular or Premium CPU, **2 vCPU / 4 GB** |
| Authentication | **SSH key**: add yours |
| Backups | optional (weekly whole-machine copies, a paid option; see step 9) |
| Hostname | for example `coop-erp-demo` |

Create it and note its **public IPv4 address**, for example `203.0.113.5`. This guide uses that example address; put yours wherever it appears.

No DNS record is needed: the demo is reached by a name made from the IP (step 5).

## 2. Connect

```bash
ssh root@203.0.113.5
```

Everything below runs on the server, as root.

## 3. Access to the code and the images

The server needs the repository (for the scripts and settings) and the container images CI publishes to GitHub's registry, GHCR (`ghcr.io/knoweb/coop_erp/...`).

**The repository.** Clone it with a GitHub token that can read it (a fine-grained personal access token with "Contents: read" on `Knoweb/COOP_ERP`), or with a deploy key:

```bash
apt-get update && apt-get install -y git
git clone https://github.com/Knoweb/COOP_ERP.git ~/COOP_ERP
# asks for a user name (your GitHub user) and a password (paste the token)
```

**The images.** If the packages on GitHub are private (the default for a private repository), sign the server in to GHCR once with a **classic** personal access token that has only the `read:packages` scope. Docker must be installed for this, so do it after step 4 if `docker` is not found yet, then run `bootstrap.sh` again:

```bash
docker login ghcr.io -u <your-github-user>
# password: the read:packages token
```

If an organisation owner has made the four packages (`backend`, `backend-worker`, `web`, `demo-tools`) public, no sign-in is needed.

## 4. Start the demo

```bash
cd ~/COOP_ERP
./infra/deploy/bootstrap.sh --size small --email you@example.com
```

`bootstrap.sh` finds the server's public IP by itself and makes the address **`https://203-0-113-5.sslip.io`** (the IP with dashes, then `.sslip.io`). sslip.io resolves every such name to the IP inside it, so the name works at once, and Caddy fetches a real Let's Encrypt certificate for it. To name the IP yourself, add `--ip 203.0.113.5`.

What it does, each step safe to repeat:

1. installs Docker Engine and the compose plugin from Docker's own apt repository;
2. creates a **2 GB swap file**, used only when memory is short (swappiness 10);
3. turns on the firewall (ufw) with only **22** (SSH), **80** (needed by Let's Encrypt to issue the certificate) and **443** (HTTPS) open;
4. copies the deployment files to `/opt/coop-erp`;
5. writes `/opt/coop-erp/.env` with strong random passwords and a new till signing key pair, **only if there is no `.env` yet** (it never overwrites one);
6. renders the Keycloak realm for this address, with the demo users' password from `.env`;
7. pins the images to the clone's commit (`IMAGE_TAG` in `.env`, once CI has published that commit; see step 7), pulls them and starts everything, and waits until every service is healthy (the first start takes a few minutes: Keycloak builds itself and imports the realm, the backend creates the database);
8. checks the address from outside and prints it.

If it stops at "pull access denied", do the GHCR sign-in of step 3 and run the same command again.

**Keep a copy of `/opt/coop-erp/.env` somewhere safe**, off the server (a password manager). It holds every password, and a backup cannot be used without it.

## 5. First sign-in

Open **https://203-0-113-5.sslip.io** in a browser. You land on the COOP ERP sign-in page.

The demo users are those of `docs/DEMO.md` (`fed-sales`, `fed-accounts`, `d101-buyer`, `m101-manager` and the rest, plus `fed-admin`). **They all share one password**, generated at bootstrap:

```bash
grep DEMO_PASSWORD /opt/coop-erp/.env
```

Other addresses on the same server:

| What | Address | Sign in with |
|---|---|---|
| Back office | `https://203-0-113-5.sslip.io` | a demo user, `DEMO_PASSWORD` |
| Mail catcher (every mail the demo sends) | `https://203-0-113-5.sslip.io/mail/` | `MAIL_USER` / `MAIL_PASSWORD` from `.env` |
| Keycloak admin console | `http://localhost:8081/auth/admin/`, through an SSH tunnel (below) | `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` from `.env` |

The users exist from the start; the business data (societies, orders, stock, sales) comes in the next step.

**The Keycloak admin console is not on the internet.** Through the public address Keycloak answers only the sign-in pages of the `coop` realm and its theme files; the admin console, the admin API, the `master` realm and the account console answer 404. Keycloak listens on the server's own loopback address (`127.0.0.1:8081`), so the console is reached through an SSH tunnel from your computer:

```bash
ssh -L 8081:127.0.0.1:8081 root@203.0.113.5
# leave it open, and browse to http://localhost:8081/auth/admin/
```

**The demo users are shared** (several testers sign in as the same user), so the demo has no self-service: no account console, no changing a password or adding a one-time code from the sign-in page. Thirty wrong passwords in a row lock a user for at most 15 minutes, never for good, and a correct sign-in clears the count. Someone can still change a demo user's password through the application itself (an administrator of the demo may, in phase 4); then put every demo user back with `demo-data.sh --repair-users` (step 8), which touches no data.

## 6. Load the demo

```bash
/opt/coop-erp/demo-data.sh
```

It loads, in this order: the seed rows, then the demo through the application itself (a one-off backend container, a few minutes), then eight weeks of till sales at the four demo shops (the `demo-tools` image, the same till simulator `make demo-data` runs on a laptop). Running it again changes nothing.

Give the testers the address and `DEMO_PASSWORD`, and point them to the storyline in `docs/DEMO.md`.

**Step-up without a second factor (demo only).** Some actions ask the user to sign in again before they go through. The demo realm has no one-time codes, so typing the password again counts; this is switched on for the demo only (`COOP_ERP_MFA_PASSWORD_REAUTH_COUNTS` in `compose.yml`, with its acknowledgement `COOP_ERP_MFA_PASSWORD_REAUTH_PUBLIC_ACK=demo-data-only`, without which the backend refuses to start on a public address). Never copy those two lines to a server with real data: there the flag is off and the realm has a second factor.

## 7. Update to a newer version

The server runs the commit the clone is at: the deployment files (compose, Caddy, the realm, the seeds) and the images are always of the same commit. CI publishes the images of every commit of `main` whose tests pass, tagged with the commit's SHA, when the pipeline of that commit has finished. To update:

```bash
cd ~/COOP_ERP && git pull --ff-only && ./infra/deploy/deploy.sh
```

`deploy.sh` runs from the clone only (it is not in `/opt/coop-erp`). In order, it:

1. refuses a clone with local changes under `infra/` or the seeds ("the clone has local changes"): the server would run them with images built without them;
2. checks that CI has published every image of the clone's commit. If not, it stops with "CI has not published this commit yet": wait until the pipeline of that commit is green (job *Package (main)*), or, when main's tests failed on it, `git checkout` an earlier commit of main and run it again;
3. writes the commit into `/opt/coop-erp/.env` as `IMAGE_TAG`, and the commit it replaces as `PREVIOUS_IMAGE_TAG`;
4. backs up both databases and the uploaded files (step 9) and prints the backup's stamp: the way back (`--no-backup` skips it);
5. copies the deployment files, pulls the images, restarts what changed, waits until healthy, applies the sign-in policy to the running Keycloak, and checks the address from outside (`ok` per check, or `FAIL` and stops), printing the last good backup;
6. removes the images of every version but these two.

The data stays; the backend brings the database up to date as it starts. To stay on a version, leave the clone where it is; to run a particular commit of main, `git checkout <sha>` in the clone, then `deploy.sh`.

**The tills' version floor.** Do not raise the tills' minimum version above a version already handed out to a till until the till updater (CR-30-1) exists: below the floor, central still takes the till's sales, but the till gets no new prices or catalogue (CR-32-1).

### Going back

A migration cannot be undone: once the backend has started, the database has the new version's shape, and an older backend would start on it without a word (Flyway ignores migrations it does not know). So going back is always a restore of the backup `deploy.sh` took, then the previous commit:

```bash
sudo /opt/coop-erp/restore.sh <stamp>        # the stamp deploy.sh printed (ls /opt/coop-erp/backups)
cd ~/COOP_ERP && git checkout <previous>      # PREVIOUS_IMAGE_TAG in /opt/coop-erp/.env
./infra/deploy/deploy.sh
```

`restore.sh` puts back the databases and the uploaded files of that moment and starts the version that made the backup; `deploy.sh` then brings the deployment files to the same commit. Everything testers did after the backup is lost. Never go back by changing `IMAGE_TAG` alone. When the fix is on main: `git checkout main && git pull --ff-only && ./infra/deploy/deploy.sh`.

## 8. Start the demo again from nothing

```bash
/opt/coop-erp/demo-data.sh --reset
```

It asks you to type `reset`, then deletes every record, user, mail and file the testers made, starts again (Keycloak imports the realm afresh) and loads the demo. The certificate and `.env` stay.

When only the demo users are broken (a tester changed a password, a user is locked out), do not reset; repair them, in about a minute, without touching any record:

```bash
/opt/coop-erp/demo-data.sh --repair-users
```

Every user of the storyline gets `DEMO_PASSWORD` back, with no pending action and no one-time code, every lockout is cleared, and the password grant is switched off. Users made in the back office are left as they are.

## 9. Backups

```bash
/opt/coop-erp/backup.sh
```

backs up to `/opt/coop-erp/backups`, under one stamp (UTC, such as `20261006-020000`): the application's database and Keycloak's (`coop_erp-<stamp>.dump`, `keycloak-<stamp>.dump`, the newest 14 of each kept), the version that made them (`<stamp>.tag`), and the files testers uploaded (GRN and claim photos, SKU images) into `backups/objects`, one current copy. `deploy.sh` runs it before every update. Every night at 02:00:

```bash
echo '0 2 * * * root /opt/coop-erp/backup.sh' > /etc/cron.d/coop-erp-backup
```

The server sends no mail, so a failing night shows up elsewhere: `backups/LAST_OK` holds the stamp of the last good backup and `backups/LAST_FAILED` that of a failed one, and every `deploy.sh` prints both.

To keep copies off the server too, create a DigitalOcean Spaces bucket and an access key, and fill in `BACKUP_S3_BUCKET`, `BACKUP_S3_ENDPOINT` (for example `https://sgp1.digitaloceanspaces.com`), `BACKUP_S3_ACCESS_KEY` and `BACKUP_S3_SECRET_KEY` in `/opt/coop-erp/.env`. The dumps, the tag files and the uploaded files are then copied there after every backup.

For the demo the dumps are not encrypted and a night's work is the most that can be lost (one backup a day). Before any real data: encrypted dumps, the `audit-anchors` bucket copied to a write-once bucket off the server, and point-in-time recovery of the database (doc 35, not written yet).

For a copy of the whole machine, turn on **Backups** for the Droplet in DigitalOcean (weekly, a paid option), or take a **Snapshot** before a risky change.

To restore a backup (the demo stops for a few minutes):

```bash
ls /opt/coop-erp/backups                     # the stamps: coop_erp-<stamp>.dump
sudo /opt/coop-erp/restore.sh <stamp>
```

It asks you to type `restore`, backs up the present state first (the undo), stops the backend, Keycloak and PgBouncer, drops both databases and restores each dump into a fresh one in a single transaction (a table a later version made cannot survive, and a restore that fails half way stops with an error, never with a half-restored database), puts the uploaded files back, starts the version that made the backup and checks the address from outside. Then run `deploy.sh` from the clone at the version you want (step 7). A restore needs the `.env` the backup was made with.

## 10. When sslip.io or Let's Encrypt cannot be reached

If the certificate cannot be issued (a network that blocks sslip.io, Let's Encrypt limits, port 80 blocked on the way), serve the bare IP with Caddy's own certificate:

```bash
cd ~/COOP_ERP
./infra/deploy/bootstrap.sh --size small --ip 203.0.113.5 --tls internal
```

The address becomes **`https://203.0.113.5`**. Each browser warns once that the certificate is not trusted: the tester chooses "Advanced", then "Continue to 203.0.113.5". To avoid the warning, install Caddy's root certificate on the testers' computers (as a trusted root authority):

```bash
docker cp coop-erp-demo-caddy-1:/data/caddy/pki/authorities/local/root.crt ./coop-erp-demo-root.crt
```

Running `bootstrap.sh` again with a new address changes only the address lines of `.env` (no password changes) and writes the new address into the running Keycloak.

## 11. Your own domain, later

When a name is ready (for example `demo.example.com`), add a DNS **A record** for it pointing to the server's IP, wait until `ping demo.example.com` answers with that IP, then:

```bash
cd ~/COOP_ERP
./infra/deploy/bootstrap.sh --size small --domain demo.example.com --email you@example.com
```

The demo moves to `https://demo.example.com`; the data and passwords stay.

## Other providers (e.g. Contabo)

Nothing in the package depends on DigitalOcean; any Ubuntu 24.04 server with root SSH and a public IPv4 works.

- **Plan**: choose 2 vCPU / 4 GB for this size. Contabo's smallest Cloud VPS is already about 4 vCPU / 8 GB: use it with **`--size medium`** and follow [SETUP-4vCPU-8GB.md](SETUP-4vCPU-8GB.md). Contabo's CPUs are shared and slower; count its 4 vCPU as roughly 2 to 4 of DigitalOcean's.
- **Region**: the closest to Sri Lanka, Singapore if offered.
- **The IP**: there is no metadata service as on DigitalOcean; `bootstrap.sh` then asks a public lookup service. If that fails, give `--ip`.
- **Firewall**: the ufw rules `bootstrap.sh` sets are the firewall (Contabo has no cloud firewall in front). Ports **80 and 443** must be reachable from the internet for the certificate; check the provider has nothing blocking them.
- **Backups**: `backup.sh` (with the provider's S3-compatible object storage if it has one) plus the provider's snapshots.

## Troubleshooting

Everything is run from `/opt/coop-erp`; this line saves typing:

```bash
cd /opt/coop-erp && C="docker compose -p coop-erp-demo --env-file .env -f compose.yml -f resources-small.yml"
```

| What you see | What to do |
|---|---|
| `bootstrap.sh` stops at "pull access denied", or "CI has not published this commit yet" although the pipeline is green | the server is not signed in to GHCR: step 3 |
| `deploy.sh` says "run deploy.sh from the clone" | `cd ~/COOP_ERP && git pull --ff-only && ./infra/deploy/deploy.sh` (step 7) |
| `deploy.sh` says "CI has not published this commit yet" | the pipeline of that commit is still running, or main's tests failed on it: wait, or `git checkout` an earlier commit of main (step 7) |
| `deploy.sh` says "the clone has local changes" | `git status` in the clone; discard them (`git checkout -- infra`) or bring them to main by a pull request |
| a demo user's password no longer works, or the user is locked | `/opt/coop-erp/demo-data.sh --repair-users` (step 8) |
| the smoke check says the password grant is on | `demo-data.sh` was interrupted: `/opt/coop-erp/demo-data.sh --repair-users` switches it off |
| `deploy.sh` prints `WARN LAST_FAILED backup` | run `/opt/coop-erp/backup.sh` by hand and read its error (often a full disk: `df -h`) |
| the browser says the site cannot be reached | `$C ps`: is every service `healthy`? `ufw status` must list 80 and 443 |
| a certificate warning on the sslip.io name | Caddy could not get the certificate yet: `$C logs caddy`. Port 80 must be reachable from the internet. If it keeps failing, step 10 |
| the sign-in page says "HTTPS required" | the address is not HTTPS; open the `https://` address `bootstrap.sh` printed |
| "Invalid parameter: redirect_uri" after sign-in | the realm has another address: run `bootstrap.sh` again with the address you use (`--ip` or `--domain`) |
| a service keeps restarting | `$C logs --tail 200 <service>`; `docker inspect --format '{{.State.OOMKilled}}' coop-erp-demo-<service>-1` says `true` if it ran out of memory: consider the medium size |
| slow when many testers act at once | expected on this size (see the top); `docker stats` shows who is busy. The medium size removes it |
| everything is slow, `free -h` shows swap nearly full | restart once (`$C restart backend keycloak`); if it comes back, take the medium size |
| no mails in the mail catcher | `$C logs backend | grep -i mail`; the catcher keeps the newest 5000 |
| disk filling up | `df -h`; old images: `docker image prune -f`; old backups: lower `BACKUP_KEEP` |

Logs of every container are capped (3 files of 10 MB each), so they cannot fill the disk.

## What runs on the server

| Service | Memory limit | Notes |
|---|---|---|
| backend | 1.5 GB | the application (web and worker roles), with Chromium for PDFs; Java heap 55 % |
| keycloak | 900 MB | sign-in; Java heap 60 % |
| postgres | 768 MB | shared_buffers 192 MB, 60 connections |
| rabbitmq | 384 MB | memory alarm at 192 MB |
| minio | 256 MB | attachments and printed PDFs |
| caddy | 128 MB | HTTPS, the web client, the proxy |
| mailpit | 64 MB | the mail catcher |
| pgbouncer | 32 MB | connection pool (15) |

Measured on a test of this size with the demo loaded and 26 browser tests run against it (four browsers at once): the whole stack peaked at about 2.4 GB; backend about 830 MB, Keycloak about 700 MB, RabbitMQ 230 MB, PostgreSQL 210 MB, Caddy 90 MB.
