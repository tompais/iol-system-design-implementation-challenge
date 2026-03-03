# Deployment Guide

## Overview

The app ships with a `compose.yaml` that starts the rate-limiter alongside a self-hosted
Grafana LGTM stack. OTLP (gRPC) is used for metrics and traces — no Prometheus scrape config needed.

---

## Live Instance (AWS EC2)

The service is publicly accessible at:

| Service | URL |
|---------|-----|
| **Rate Limiter API** | `http://ec2-56-124-56-96.sa-east-1.compute.amazonaws.com:8080` |
| **Swagger UI** | `http://ec2-56-124-56-96.sa-east-1.compute.amazonaws.com:8080/swagger-ui.html` |
| **OpenAPI JSON** | `http://ec2-56-124-56-96.sa-east-1.compute.amazonaws.com:8080/v3/api-docs` |
| **Grafana Dashboards** | `http://ec2-56-124-56-96.sa-east-1.compute.amazonaws.com:3000` |
| **Prometheus** | `http://ec2-56-124-56-96.sa-east-1.compute.amazonaws.com:9090` |

Quick smoke test against the live instance:

```bash
curl -X POST http://ec2-56-124-56-96.sa-east-1.compute.amazonaws.com:8080/api/rate-limit/check \
  -H 'Content-Type: application/json' \
  -d '{"key":"demo-user"}'
# → 200 {"allowed":true}
```

### Port Mapping

| Port | Protocol | Service | Exposed to |
|------|----------|---------|------------|
| 22 | TCP | SSH — instance administration | Operator IP only |
| 8080 | TCP | Rate Limiter API (Spring Boot / Netty) | `0.0.0.0/0` (public) |
| 3000 | TCP | Grafana (dashboards, alerts) | `0.0.0.0/0` (public, Grafana login required — see note) |
| 9090 | TCP | Prometheus (metrics scraping + query UI) | `0.0.0.0/0` (public) |

> **Note:** Grafana's HTTP endpoint is publicly reachable for demo purposes, but the instance still requires a Grafana login (no anonymous dashboards). The demo environment uses the default `admin / admin` credentials. **Change these immediately after any deployment that is publicly reachable — never leave default credentials on a live instance.** In a production environment, either restrict port 3000 to your operator IP range or put Grafana behind an authenticated reverse proxy and configure per-user accounts.

> **Security notice:** Ports 3000 (Grafana) and 9090 (Prometheus) are exposed publicly for demo/evaluation purposes only. These services expose operational metrics and dashboards that can reveal system topology, versions, and internal details. For any non-demo deployment, restrict these ports to operator IPs/VPN only or place them behind an authenticated reverse proxy.

---

## Why AWS EC2?

EC2 was chosen for the following reasons:

1. **Free-tier eligible.** The `t2.micro` (1 vCPU, 1 GB RAM) is free for 750 hours/month for 12 months — enough for continuous 24/7 operation at no cost during the evaluation period.

2. **Single-instance simplicity matches the prototype scope.** This service intentionally uses in-memory state (`ConcurrentHashMap` + `AtomicReference`). A single EC2 instance is the correct deployment target for in-memory rate limiting — no distributed state is needed. Adding a load balancer or auto-scaling group would require an external store (Redis) to avoid independent token budgets per instance, which is intentionally out of scope for this challenge (see the [Storage trade-off](../rate-limiter/DESIGN.md#trade-offs) section).

3. **Full control over the runtime environment.** EC2 gives direct access to the JVM flags (`-XX:+UseZGC`, `-Xmx256m`), the Docker daemon, and the Compose stack — unlike managed PaaS platforms (ECS, App Runner, Heroku) which abstract these away.

4. **Region: `sa-east-1` (São Paulo).** Chosen for proximity to the submission audience. Low network latency produces accurate `Retry-After` header values in live demos.

5. **Automated CD pipeline.** The `.github/workflows/cd.yml` workflow SSH-deploys the latest build on every merge to `master`, keeping the live instance always current without manual intervention.

---

## Local (Docker Compose)

**Prerequisites:** Docker with the Compose plugin installed.

```bash
# Start app + Grafana LGTM stack
docker compose up

# Detached
docker compose up -d

# Rebuild app image after code changes
docker compose up --build
```

| Service                        | URL                   |
|--------------------------------|-----------------------|
| App                            | http://localhost:8080 |
| Grafana                        | http://localhost:3000 |
| Prometheus-compatible endpoint | http://localhost:9090 |

Smoke test:
```bash
curl -X POST http://localhost:8080/api/rate-limit/check \
  -H 'Content-Type: application/json' \
  -d '{"key":"user-1"}'
# → 200 {"allowed":true}
```

Health check:
```bash
curl http://localhost:8080/actuator/health
# → {"status":"UP","components":{...}}
```

---

## AWS EC2 Free Tier (24/7 hosting)

AWS EC2 t2.micro is **750 hours/month free for 12 months** — enough for continuous operation.

### 1. Launch an EC2 instance

- AMI: **Amazon Linux 2023** (free tier eligible)
- Instance type: **t2.micro** (1 vCPU, 1 GB RAM)
- Storage: 8 GB gp2 (default)
- Key pair: create or select an existing `.pem` key

### 2. Security group rules

| Type       | Protocol | Port | Source                      |
|------------|----------|------|-----------------------------|
| SSH        | TCP      | 22   | Your IP (e.g. `x.x.x.x/32`) |
| Custom TCP | TCP      | 8080 | `0.0.0.0/0` (API)           |
| Custom TCP | TCP      | 3000 | `0.0.0.0/0` (Grafana)       |

Restrict port 3000 to your IP in production — Grafana has no auth by default.

### 3. Install Docker on Amazon Linux 2023

```bash
# Connect to the instance
ssh -i your-key.pem ec2-user@<public-ip>

# Install Docker
sudo dnf install -y docker
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker ec2-user   # allows running docker without sudo
# Re-login for group change to take effect

# Install Compose plugin
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
```

### 4. Clone and run

```bash
git clone https://github.com/tompais/iol-system-design-implementation-challenge.git
cd iol-system-design-implementation-challenge/sd-implementation-challenge

# First run builds the app image (~3–5 min on t2.micro)
docker compose up -d

# Follow logs
docker compose logs -f app
```

### 5. Verify

```bash
# From EC2 or your local machine (replace <public-ip>)
curl -X POST https://<public-ip>:8080/api/rate-limit/check \
  -H 'Content-Type: application/json' \
  -d '{"key":"smoke-test"}'
# → 200 {"allowed":true}
```

Grafana: open `http://<public-ip>:3000` in a browser (default credentials: admin / admin).

---

## JVM Sizing Rationale (t2.micro, 1 GB total)

The `Dockerfile` sets these JVM flags via `JAVA_OPTS`:

| Flag                          | Value  | Reason                                                    |
|-------------------------------|--------|-----------------------------------------------------------|
| `-XX:+UseZGC`                 | —      | Sub-millisecond GC pauses — ideal for reactive workloads  |
| `-Xmx256m`                    | 256 MB | App heap ceiling; leaves ~700 MB for LGTM stack           |
| `-XX:MaxDirectMemorySize`     | 128 MB | Netty direct buffer cap — off-heap, not counted in `-Xmx` |
| `-XX:+ExitOnOutOfMemoryError` | —      | Crash-fast; Docker restarts the container                 |

Typical memory footprint:
- App JVM (heap + direct): ~256 MB + 128 MB = ~384 MB
- Grafana LGTM container: ~400–450 MB
- OS + Docker overhead: ~100 MB
- **Total: ~884 MB — fits within 1 GB**

---

## Grafana Dashboards

Import pre-built dashboards from grafana.com after the stack starts:

| Dashboard              | ID    |
|------------------------|-------|
| Spring Boot Statistics | 12685 |
| JVM (Micrometer)       | 4701  |

In Grafana: **Dashboards → Import → paste ID → Load**.

---

## Updating the App

```bash
git pull
docker compose up --build -d
```

The `--build` flag rebuilds the app image; `grafana-lgtm` is not rebuilt (uses the existing image).

---

## Stopping

```bash
docker compose down          # stop containers, keep volumes (Grafana data retained)
docker compose down -v       # stop + delete volumes (Grafana data wiped)
```

---

## Automated Deployment (CD)

The `.github/workflows/cd.yml` workflow redeploys the app to EC2 automatically on every merge to `master`, but only when the CI pipeline has passed.

### How it works

1. A merge to `master` triggers the CI workflow (`ci.yml`)
2. CI builds, tests, lints, and runs k6 load tests
3. On CI success, the `docker` job (scoped to `packages: write`) builds the Docker image using BuildKit (with GitHub Actions layer cache) and pushes `:latest` + `:<sha>` to `ghcr.io/tompais/iol-system-design-implementation-challenge`
4. The `cd.yml` `workflow_run` trigger fires after CI completes
5. The deploy job SSHes into the EC2 instance: checks Docker + Compose are available, downloads `compose.yaml` pinned to the triggering commit SHA via `curl`, writes `IMAGE_TAG=<sha>` to a `.env` file, pulls the exact SHA-tagged image from ghcr.io, and starts the app container — **no git, no Gradle build on EC2**
6. Only the app container is updated (`--no-deps`) — `grafana-lgtm` keeps running with its existing image unchanged

> **Why no `--build` on EC2?** A t2.micro (1 vCPU, 1 GB RAM) cannot reliably run a Gradle build inside Docker. The image is built once in CI where resources are ample, tagged with both `:latest` and the commit SHA, pushed to ghcr.io, then pulled on EC2. This eliminates the timeout that occurred when Gradle ran on the instance. No git clone is needed either — the only file required to run `docker compose up -d --no-deps app` is `compose.yaml`, which is fetched directly from the raw GitHub URL pinned to the same SHA that built the image.

### One-time EC2 prerequisites

Only Docker must be installed on the instance (see [AWS EC2 Free Tier](#aws-ec2-free-tier-247-hosting)). No git, no repo clone, no Gradle. The CD workflow installs Docker and the Compose plugin automatically on first run if they are missing.

> **One-time package visibility**: After the first CI push, navigate to **GitHub repo → Packages → `iol-system-design-implementation-challenge` → Package settings → Change visibility → Public**. This lets EC2 pull the image without a long-lived PAT. The CD workflow uses the short-lived `GITHUB_TOKEN` for `docker login`, so the pull works automatically from the first deploy onward.

### Required GitHub Secrets

Configure these under **GitHub repo → Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|--------|-------|
| `EC2_HOST` | Public IP or DNS of the EC2 instance (e.g. `1.2.3.4`) |
| `EC2_USERNAME` | SSH username — `ec2-user` on Amazon Linux |
| `EC2_SSH_KEY` | Full contents of the `.pem` private key file |
| `EC2_HOST_FINGERPRINT` | SSH host key fingerprint of the EC2 instance (run `ssh-keyscan -t ed25519 <host>` to obtain a single known-hosts line) |
| `EC2_REPO_PATH` | Absolute path on EC2 (e.g. `~/iol-system-design-implementation-challenge`) |

### Restart policy

Both services in `compose.yaml` include `restart: unless-stopped`. This ensures the containers automatically restart after an EC2 instance reboot (e.g. scheduled maintenance, stop/start), without any manual intervention.

### Verify after deploy

```bash
curl https://<ec2-ip>:8080/actuator/health
# → {"status":"UP"}
```
