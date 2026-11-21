# Put PayFlash live on Oracle Cloud (free)

The result is a public HTTPS link like `https://140-238-1-23.sslip.io`. You can send it to HR or put it on your CV.
Visitors can click **"Continue as Priya Sharma"** and use everything with play money, or sign up with their own
email (they get a real verification email).

**Cost:** ₹0. Everything below fits Oracle's *Always Free* limits. **Time:** about 45 minutes, most of it waiting.

Steps 1-4 happen in your accounts. Steps 5-6 are one script.

---

## 1. Create the Oracle Cloud account

1. Go to <https://www.oracle.com/cloud/free/> → **Start for free**.
2. **Home region:** choose **India West (Mumbai)** or **India South (Hyderabad)**. You can't change it later. If
   Oracle later says "Out of capacity" for the free server, that region is busy; waiting a few hours usually helps.
3. A card is needed for identity verification. Always Free resources are not charged.

> Tip: Oracle can reclaim *Always Free* servers that sit almost idle for a week. Upgrading the account to
> **Pay As You Go** (Billing → Upgrade) stops that, and you still pay nothing while you stay within the free limits.

## 2. Create the server

Console menu → **Compute → Instances → Create instance**:

| Setting | Choose |
|---|---|
| Name | `payflash` |
| Image | **Canonical Ubuntu 24.04** |
| Shape | **Ampere → VM.Standard.A1.Flex**, **4 OCPUs, 24 GB memory** (the free maximum) |
| Networking | Create a new virtual cloud network + **public subnet**, **Assign a public IPv4 address: yes** |
| SSH keys | **Generate a key pair for me** → **Save private key** (keep this file safe, e.g. `C:\Users\you\.ssh\payflash.key`) |
| Boot volume | 50 GB is plenty |

Click **Create**. When it's *Running*, copy the **Public IP address**.

## 3. Open the web ports

On the instance page → the **Subnet** link → **Security Lists** → the default list → **Add Ingress Rules**:

| Source CIDR | IP protocol | Destination port |
|---|---|---|
| `0.0.0.0/0` | TCP | `80` |
| `0.0.0.0/0` | TCP | `443` |

(The setup script also opens them in the server's own firewall, which Oracle's Ubuntu image blocks by default.)

## 4. Create the free Brevo account (emails)

1. Sign up at <https://www.brevo.com> (free plan: 300 emails a day).
2. **Senders, domains & dedicated IPs → Senders → Add a sender**: your email address. Click the confirmation
   link Brevo sends you.
3. **SMTP & API → SMTP tab → Generate a new SMTP key**. Keep that page open: you need the **Login** and the
   **SMTP key** in step 6. Don't paste them into chat or commit them anywhere.

> Emails sent "from" a Gmail address through Brevo sometimes land in spam. That's fine for a demo (HR can use
> the one-click demo accounts). For better delivery, buy a domain and verify it in Brevo.

## 5. Connect to the server

From PowerShell on your PC:

```powershell
ssh -i C:\Users\you\.ssh\payflash.key ubuntu@<public-ip>
```

If Windows says the key's permissions are too open:

```powershell
icacls C:\Users\you\.ssh\payflash.key /inheritance:r /grant:r "$($env:USERNAME):R"
```

## 6. Install and start PayFlash

On the server:

```bash
git clone https://github.com/EvilWithin3812/payflash.git
cd payflash
bash deploy/setup-server.sh
```

The first run installs Docker, opens the firewall, creates `.env` with random passwords, and stops to ask for
your Brevo details. Fill them in:

```bash
nano .env
```

```
MAIL_USERNAME=<Brevo SMTP login, e.g. 8f1e2a001@smtp-brevo.com>
MAIL_PASSWORD=<Brevo SMTP key>
MAIL_FROM="PayFlash <the sender email you verified in Brevo>"
```

Save (`Ctrl+O`, `Enter`, `Ctrl+X`) and run the script again:

```bash
bash deploy/setup-server.sh
```

It installs Caddy (free HTTPS certificate), builds everything (10-20 minutes the first time) and finally prints:

```
PayFlash is live: https://140-238-1-23.sslip.io
```

That's the link to share.

## 7. Check it

- Open the link → **Continue as Priya Sharma** → you're in with ₹5,000 play money.
- **Flash sales** always has a live sale. Open it as Priya in one browser and as Rahul in another (or a private
  window) and watch the stock counter move.
- Sign up with your own email → the verification email arrives through Brevo.
- The admin account (for creating your own sales) is in `.env`: `grep ADMIN_ .env`.

## Everyday commands (on the server, in ~/payflash)

```bash
git pull && bash deploy/setup-server.sh          # update to the latest code
sudo docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.demo.yml ps
sudo docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.demo.yml logs -f app
ls -lh backups/                                  # daily database backups
```

Grafana and Zipkin stay private. To look at them, open a tunnel from your PC and browse to
<http://localhost:3001> (Grafana, user `admin`, password `GRAFANA_ADMIN_PASSWORD` from `.env`) and
<http://localhost:9411> (Zipkin):

```powershell
ssh -i C:\Users\you\.ssh\payflash.key -L 3001:localhost:3001 -L 9411:localhost:9411 ubuntu@<public-ip>
```

**Down alerts:** add a free [UptimeRobot](https://uptimerobot.com) monitor for `https://<your-link>/api/v1/health`.

## What the demo mode does

`docker-compose.demo.yml` runs the production setup (random secrets, Redis password, nothing exposed but the
site) with `DEMO_MODE=true`:

- Two shared accounts (Priya, Rahul) with play money, topped back up automatically, shown as one-click buttons
  on the login page. They can't be deleted, password-reset or "logged out everywhere" by visitors.
- "Add money" is free play money; no real payments are possible.
- A flash sale is always live: when one ends or sells out, a new one starts within a minute.
- A banner on every page says it's a demo with play money.
