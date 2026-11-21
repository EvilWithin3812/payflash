#!/usr/bin/env bash
# One-command setup of the live PayFlash demo on a fresh Ubuntu server (made for an Oracle Cloud free Arm VM,
# works on any Ubuntu 22.04/24.04 host). Run it from the cloned repository:
#
#   cd ~/payflash && bash deploy/setup-server.sh
#
# Safe to run again: it keeps an existing .env and only rebuilds and restarts what changed.
# Full walkthrough: deploy/ORACLE.md
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_DIR=$(pwd)
COMPOSE="sudo docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.demo.yml"

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

# ------------------------------------------------------------------ 1. Docker
if ! command -v docker >/dev/null 2>&1; then
    step "Installing Docker"
    curl -fsSL https://get.docker.com | sudo sh
    sudo usermod -aG docker "$USER" || true
else
    step "Docker is already installed"
fi

# ------------------------------------------------------------------ 2. Firewall
# Oracle's Ubuntu images ship iptables rules that reject everything except SSH, even after you open the ports
# in the cloud console. Allow web traffic (80 for the HTTPS certificate check, 443 for the site).
step "Opening ports 80 and 443 in the server firewall"
for port in 80 443; do
    if ! sudo iptables -C INPUT -p tcp --dport "$port" -m conntrack --ctstate NEW -j ACCEPT 2>/dev/null; then
        sudo iptables -I INPUT 1 -p tcp --dport "$port" -m conntrack --ctstate NEW -j ACCEPT
    fi
done
if command -v netfilter-persistent >/dev/null 2>&1; then
    sudo netfilter-persistent save >/dev/null
else
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent >/dev/null
    sudo netfilter-persistent save >/dev/null
fi

# ------------------------------------------------------------------ 3. Address
# Without a domain of your own, <ip-with-dashes>.sslip.io points at this server for free (no sign-up), and
# Let's Encrypt can issue an HTTPS certificate for it.
if [ -z "${DOMAIN:-}" ] && [ -f .env ] && grep -q '^PUBLIC_ORIGIN=https://' .env; then
    DOMAIN=$(grep '^PUBLIC_ORIGIN=' .env | sed 's#PUBLIC_ORIGIN=https://##')
fi
if [ -z "${DOMAIN:-}" ]; then
    PUBLIC_IP=$(curl -fsS https://api.ipify.org)
    DOMAIN="${PUBLIC_IP//./-}.sslip.io"
fi
step "Site address: https://$DOMAIN"

# ------------------------------------------------------------------ 4. Secrets (.env)
rand() { openssl rand -base64 48 | tr -d '\n/+=' | cut -c1-"${1:-40}"; }
if [ ! -f .env ]; then
    step "Creating .env with fresh random secrets"
    umask 077
    cat > .env <<EOF
# Created by deploy/setup-server.sh on $(date -u +%F). Keep this file private; never commit it.
JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
POSTGRES_PASSWORD=$(rand)
NOTIFICATIONS_DB_PASSWORD=$(rand)
REDIS_PASSWORD=$(rand)
GRAFANA_ADMIN_PASSWORD=$(rand 20)
ADMIN_EMAIL=admin@$DOMAIN
ADMIN_PASSWORD=$(rand 20)
PUBLIC_ORIGIN=https://$DOMAIN

# Email (Brevo SMTP: https://app.brevo.com/settings/keys/smtp). Fill these in, then run this script again.
MAIL_HOST=smtp-relay.brevo.com
MAIL_PORT=587
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM="PayFlash <you@example.com>"

# Optional: Razorpay test-mode keys (not needed for the demo; "Add money" uses play money)
RAZORPAY_KEY_ID=
RAZORPAY_KEY_SECRET=
RAZORPAY_WEBHOOK_SECRET=
EOF
    umask 022
else
    step "Keeping the existing .env"
fi

missing=""
for var in MAIL_USERNAME MAIL_PASSWORD; do
    grep -q "^$var=..*" .env || missing="$missing $var"
done
if grep -q '^MAIL_FROM=.*you@example.com' .env; then missing="$missing MAIL_FROM"; fi
if [ -n "$missing" ]; then
    printf '\n\033[1;33mAlmost there. Fill in your Brevo SMTP details in .env:%s\033[0m\n' "$missing"
    echo "  nano $REPO_DIR/.env"
    echo "Then run this script again:  bash deploy/setup-server.sh"
    exit 1
fi

# ------------------------------------------------------------------ 5. Caddy (HTTPS)
if ! command -v caddy >/dev/null 2>&1; then
    step "Installing Caddy (automatic HTTPS certificates)"
    sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl gnupg >/dev/null
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
        | sudo gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
        | sudo tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
    sudo apt-get update >/dev/null
    sudo apt-get install -y caddy >/dev/null
fi
step "Configuring Caddy for $DOMAIN"
sudo tee /etc/caddy/Caddyfile >/dev/null <<EOF
$DOMAIN {
    encode gzip
    header Strict-Transport-Security "max-age=31536000"
    reverse_proxy 127.0.0.1:8080
}
EOF
sudo systemctl reload caddy || sudo systemctl restart caddy

# ------------------------------------------------------------------ 6. Start
step "Building and starting PayFlash (the first build takes 10-20 minutes on a small Arm server)"
$COMPOSE up -d --build

step "Waiting for the app to become healthy"
for _ in $(seq 1 90); do
    if curl -fsS http://127.0.0.1:8080/api/v1/health 2>/dev/null | grep -q UP; then
        printf '\n\033[1;32mPayFlash is live: https://%s\033[0m\n' "$DOMAIN"
        echo "Admin login (to create flash sales): see ADMIN_EMAIL / ADMIN_PASSWORD in $REPO_DIR/.env"
        echo "Logs:    $COMPOSE logs -f app"
        echo "Status:  $COMPOSE ps"
        exit 0
    fi
    sleep 10
done
echo "The app did not report healthy within 15 minutes. Check:  $COMPOSE ps  and  $COMPOSE logs app" >&2
exit 1
