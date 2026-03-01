# Deployment Guide

## Overview

The app ships with a `compose.yaml` that starts the rate-limiter alongside a self-hosted
Grafana LGTM stack. OTLP (gRPC) is used for metrics and traces — no Prometheus scrape config needed.

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
- Storage: **20 GB gp3** (the default 8 GB fills up quickly once Docker images are cached; increase to 20 GB at no meaningful cost)
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

# Install git and Docker
sudo dnf install -y git docker
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker ec2-user   # allows running docker without sudo
# Re-login for group change to take effect

# Install Compose plugin (includes a recent buildx — required >= 0.17.0)
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# Verify versions
docker compose version   # must be >= 2.24.x
docker buildx version    # must be >= v0.17.0
```

> **Note:** `docker compose build` requires buildx ≥ 0.17.0. If you installed Docker from
> the Amazon Linux repos earlier (before following this guide), your bundled buildx may be
> older. Installing the Compose plugin from GitHub (as above) always ships a current buildx.

> **Memory note:** The Gradle Kotlin build inside Docker uses ~512 MB at peak. On t2.micro
> (1 GB total), the first `--build` may be slow; subsequent builds reuse the build cache and
> are faster. If the build OOMs, stop the `grafana-lgtm` container first, rebuild, then
> restart it.

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
curl -X POST http://<public-ip>:8080/api/rate-limit/check \
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
2. On CI success, the `cd.yml` `workflow_run` trigger fires
3. The deploy job SSHes into the EC2 instance, pulls the latest code, and runs `docker compose up --build -d app`
4. Only the app container is rebuilt — `grafana-lgtm` keeps running with the existing image

### One-time EC2 prerequisites

The manual setup steps in [AWS EC2 Free Tier](#aws-ec2-free-tier-247-hosting) must be completed once (Docker installed, repo cloned). After that, all updates are automated.

### Required GitHub Secrets

Configure these under **GitHub repo → Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|--------|-------|
| `EC2_HOST` | Public IP or DNS of the EC2 instance (e.g. `1.2.3.4`) |
| `EC2_USERNAME` | SSH username — `ec2-user` on Amazon Linux |
| `EC2_SSH_KEY` | Full contents of the `.pem` private key file |
| `EC2_REPO_PATH` | Absolute path to the `sd-implementation-challenge/` directory on EC2 (e.g. `~/iol-system-design-implementation-challenge/sd-implementation-challenge`) |

### Restart policy

Both services in `compose.yaml` include `restart: unless-stopped`. This ensures the containers automatically restart after an EC2 instance reboot (e.g. scheduled maintenance, stop/start), without any manual intervention.

### Verify after deploy

```bash
curl http://<ec2-ip>:8080/actuator/health
# → {"status":"UP"}
```
