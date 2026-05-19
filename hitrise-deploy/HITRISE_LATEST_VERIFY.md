# HitRise latest verification

Generated: 2026-05-19 07:03:30

- Local root: D:\2026\202605\hitrise
- Android source: D:\2026\202605\hitrise\hitrise-android
- Server source: D:\2026\202605\hitrise\hitrise-server
- App name: HitRise
- Android applicationId: com.zclei.hitrise
- API Base URL: http://152.136.62.157/hitrise/api/v1/
- Database: hitrise
- Product code: HTR01
- Remote service directory: /opt/hitrise-auth
- Remote upload directory: /opt/hitrise-auth/uploads
- Remote log directory: /var/log/hitrise-auth
- Runtime env file: /etc/hitrise-auth.env
- systemd service: hitrise-auth.service
- Service port: 127.0.0.1:8014
- Nginx snippet: /etc/nginx/snippets/hitrise-auth-location.conf
- Nginx entry: /hitrise/
- Public health check: http://152.136.62.157/hitrise/health
- Public OpenAPI: http://152.136.62.157/hitrise/openapi.json

## Verification

- hitrise-auth.service is active and running.
- Local health check returned {"status":"ok","service":"hitrise-auth"}.
- Public health check returned {"status":"ok","service":"hitrise-auth"}.
- Nginx proxies /hitrise/ to 127.0.0.1:8014.
- Remote database hitrise contains current project schema tables: pp_users, 	raining_sessions, user_achievements.
- Runtime snapshot is saved under D:\2026\202605\hitrise\hitrise-deploy\runtime.