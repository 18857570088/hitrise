# Hitrise Project Plan

## Project Identity

- Project name: Hitrise
- App display name: HitRise
- Android applicationId: com.zclei.hitrise
- Local root: D:\2026\202605\hitrise
- Android source: D:\2026\202605\hitrise\hitrise-android
- Server source: D:\2026\202605\hitrise\hitrise-server
- Deploy files: D:\2026\202605\hitrise\hitrise-deploy
- Docs: D:\2026\202605\hitrise\hitrise-docs

## Server Deployment

- Server IP: 152.136.62.157
- Database: hitrise
- Database user: wm
- Remote service directory: /opt/hitrise-auth
- Upload directory: /opt/hitrise-auth/uploads
- Log directory: /var/log/hitrise-auth
- Runtime config: /etc/hitrise-auth.env
- systemd service: hitrise-auth.service
- Internal port: 127.0.0.1:8014
- Nginx snippet: /etc/nginx/snippets/hitrise-auth-location.conf
- Nginx entry: /hitrise/
- API Base URL: http://152.136.62.157/hitrise/api/v1/
- Product code: HTR01

## Current Status

- Android source has been migrated from Smart sensor ball to HitRise.
- Server code has been migrated to an independent Hitrise service.
- Database hitrise has been created with the current schema.
- Public health check: http://152.136.62.157/hitrise/health
- APK output: D:\2026\202605\hitrise\hitrise-deploy\apk\HitRise.apk

## Runtime Snapshot

Sensitive runtime parameters are saved locally under:

D:\2026\202605\hitrise\hitrise-deploy\runtime

Do not upload or publicly share runtime\hitrise-auth.env.