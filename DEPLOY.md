# Deploying PayFlash

The same Docker images that run locally run in production. Two options are described: a **single EC2
instance** running Docker Compose (cheapest, fits the AWS free tier with care), and the **managed AWS
services** route for real traffic.

## Option A: one EC2 instance with Docker Compose

Good for a portfolio demo with a public URL.

### 1. Create the server

1. AWS console → EC2 → **Launch instance**.
   - Image: **Ubuntu Server 24.04 LTS**.
   - Type: **t3.large** (2 vCPU, 8 GB) runs everything, including Grafana, Zipkin and Prometheus. A free-tier
     `t3.micro` (1 GB) is **too small** for Kafka + 3 JVMs. Either pay for a larger instance for the demo period
     or use option B's managed services for the heavy parts.
   - Storage: 30 GB gp3.
   - Key pair: create one and download the `.pem` file.
2. Security group, inbound rules:
   - SSH (22) from **your IP only**
   - HTTP (80) and HTTPS (443) from anywhere

   Nothing else: databases, Kafka and the admin UIs stay private.

### 2. Install Docker

```bash
ssh -i payflash.pem ubuntu@<public-ip>
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu && exit   # log in again so the group applies
```

### 3. Get the code and set secrets

```bash
git clone https://github.com/EvilWithin3812/payflash.git && cd payflash
cp .env.example .env
nano .env        # fill in every value; generate secrets as shown in the file
```

### 4. Start

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
docker compose ps        # wait for app, gateway, notification-service to be (healthy)
```

The app is now on `http://<public-ip>`.

### 5. HTTPS

Point a domain (or a free subdomain from DuckDNS) at the instance, then put [Caddy](https://caddyserver.com) in
front. It fetches and renews Let's Encrypt certificates automatically:

```bash
# change web's port mapping to "127.0.0.1:8080:80" in docker-compose.prod.yml, then:
sudo apt install -y caddy
sudo tee /etc/caddy/Caddyfile <<'EOF'
payflash.example.com {
    header Strict-Transport-Security "max-age=31536000; includeSubDomains"
    reverse_proxy 127.0.0.1:8080
}
EOF
sudo systemctl reload caddy
```

Set `PUBLIC_ORIGIN=https://payflash.example.com` in `.env` and restart. The links in verification and
password-reset emails are built from it.

### 6. Look inside (privately)

Grafana, Prometheus and Zipkin listen on the server's localhost only. Tunnel to them:

```bash
ssh -i payflash.pem -L 3001:localhost:3001 -L 9411:localhost:9411 ubuntu@<public-ip>
# then open http://localhost:3001 (Grafana) and http://localhost:9411 (Zipkin)
```

### 7. Updates

```bash
git pull && docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

### 8. Backups

The `backup` container dumps both databases once at start-up and then every 24 hours into `./backups` on the
server (kept 14 days in production). Check it:

```bash
ls -lh backups/                      # payflash-<date>.dump and notifications-<date>.dump
docker compose logs backup           # "backup ok" lines
```

**Copy them off the server**: a backup on the same disk is lost with the disk. For example, to Amazon S3 every
night (`aws configure` once, then `crontab -e`):

```bash
30 3 * * * aws s3 sync /home/ubuntu/payflash/backups s3://YOUR-BUCKET/payflash-backups --only-show-errors
```

Or simply download them now and then: `scp -i payflash.pem -r ubuntu@<public-ip>:payflash/backups .`

**Restore** (test this once before you need it):

```bash
docker compose stop app notification-service
docker compose exec -T postgres pg_restore -U payflash -d payflash --clean --if-exists < backups/payflash-<date>.dump
docker compose exec -T notifications-db pg_restore -U notifications -d notifications --clean --if-exists   < backups/notifications-<date>.dump
docker compose start app notification-service
```

### 9. Alerts when the site goes down

1. Sign up at [UptimeRobot](https://uptimerobot.com) (free) or Better Stack.
2. Add an **HTTP(s) monitor** for `https://your-domain/api/v1/health`, every 5 minutes, alerting your email
   (and the mobile app, if you like). That URL goes through nginx, the gateway and the app to the database,
   Redis and Kafka, so it only answers `200 {"status":"UP"}` when the whole chain works.
3. Add a second **keyword monitor** for `https://your-domain/` with keyword `PayFlash`, to catch a broken frontend.

Inside the server, Prometheus also evaluates alert rules ([monitoring/alerts.yml](monitoring/alerts.yml)): service
down, 5xx error rate, slow responses, outbox backlog, Kafka lag, open circuit breakers and suspected password
guessing. See them at `http://localhost:9090/alerts` through the SSH tunnel.

### 10. Before real users: fill in the legal pages

The Terms, Privacy and Refund pages (`/terms`, `/privacy`, `/refunds`) are templates. Put your business name,
address, support and grievance-officer contacts in
[frontend/src/legal/site.ts](frontend/src/legal/site.ts), read every page, and have a lawyer or CA check them for
what you actually sell. Razorpay also asks for these pages during KYC.

## Option B: managed AWS services

The containers are the same. Only the connection settings change.

| Local container | AWS service | Setting to change |
|---|---|---|
| `postgres`, `notifications-db` | **RDS for PostgreSQL** (two databases, or two instances) | `DB_URL`, `DB_USER`, `DB_PASSWORD` per service |
| `redis` | **ElastiCache for Redis** | `REDIS_HOST`, `REDIS_PORT` |
| `kafka` | **Amazon MSK** (or Confluent Cloud) | `KAFKA_BOOTSTRAP` (+ TLS/SASL client properties) |
| `mailpit` | **Amazon SES** SMTP | `MAIL_HOST`, `MAIL_PORT=587`, `MAIL_USERNAME`, `MAIL_PASSWORD` |
| `app`, `gateway`, `notification-service`, `web` | **ECS Fargate** services behind an **Application Load Balancer** | image from **ECR**; secrets from **Secrets Manager** |
| `prometheus`, `grafana`, `zipkin` | **Amazon Managed Prometheus / Grafana**, **X-Ray** or Grafana Cloud | scrape config / `ZIPKIN_ENDPOINT` |

Notes:
- **Run several `app` instances.** Correctness holds across instances by design: rate limits and sale stock live
  in Redis, the outbox and hold-expiry jobs use `FOR UPDATE SKIP LOCKED`, and Kafka consumer groups split the partitions.
- **Never put RDS or ElastiCache on a public subnet.** Only the load balancer is public.
- **Frontend:** you can also build `frontend/` and host it on S3 + CloudFront (or Vercel), with `/api` and `/ws`
  routed to the ALB.

## Production checklist

The prod override sets `PRODUCTION=true`, which makes the app **refuse to start** if the dev JWT key, simulated
top-ups, disabled email, unverified-email shopping or a localhost `PUBLIC_URL` slipped through. Set
`PRODUCTION=true` on any other deployment too (ECS task definitions, etc.).

- [ ] `JWT_SECRET`: new random value (`openssl rand -base64 48`), the same for `app` and `notification-service`
- [ ] Database, admin and Grafana passwords: random, stored only in `.env` / Secrets Manager
- [ ] `SIMULATED_TOPUP_ENABLED=false` (the prod override does this)
- [ ] Razorpay: switch from test keys to live keys only after KYC. Register the webhook URL
      `https://<domain>/api/v1/payments/webhook` with a strong `RAZORPAY_WEBHOOK_SECRET`
- [ ] Real SMTP (SES, Brevo, Postmark, ...) instead of Mailpit, with `MAIL_FROM` on a domain you verified there
      (SPF and DKIM records), so verification and reset emails don't land in spam
- [ ] `REDIS_PASSWORD` set (the prod override makes Redis require it)
- [ ] Legal pages filled in ([frontend/src/legal/site.ts](frontend/src/legal/site.ts)) and reviewed
- [ ] Uptime monitor on `/api/v1/health`; backups copied off the server
- [ ] HTTPS everywhere; only ports 80/443 open to the internet
- [ ] A backup restore tested once
- [ ] Trace sampling at 10% (`TRACING_SAMPLING_PROBABILITY=0.1`, the prod override does this)
- [ ] Next hardening step: sign JWTs with RS256 and publish a JWKS endpoint, so other services can verify tokens without holding the signing key
