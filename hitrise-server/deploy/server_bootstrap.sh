#!/usr/bin/env bash
set -euo pipefail

sudo apt-get update
sudo apt-get install -y python3 python3-venv python3-pip mysql-client nginx

sudo mkdir -p /opt/hitrise-auth /opt/hitrise-auth/uploads /var/log/hitrise-auth
sudo chown -R ubuntu:ubuntu /opt/hitrise-auth /var/log/hitrise-auth

cd /opt/hitrise-auth
python3 -m venv .venv
. .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt

sudo cp deploy/systemd/hitrise-auth.service /etc/systemd/system/hitrise-auth.service
sudo cp deploy/nginx/hitrise-auth-location.conf /etc/nginx/snippets/hitrise-auth-location.conf
if ! grep -q 'hitrise-auth-location.conf' /etc/nginx/sites-available/reflex-auth.conf; then
  sudo sed -i '/location \/ {/i\    include /etc/nginx/snippets/hitrise-auth-location.conf;\n' /etc/nginx/sites-available/reflex-auth.conf
fi
sudo systemctl daemon-reload
sudo systemctl enable hitrise-auth
sudo systemctl restart hitrise-auth
sudo nginx -t
sudo systemctl reload nginx
