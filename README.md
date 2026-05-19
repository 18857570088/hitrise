# HitRise

HitRise is the Hitrise Android and cloud service project.

## Project Layout

- `hitrise-android/` - Kotlin Android app, applicationId `com.zclei.hitrise`.
- `hitrise-server/` - FastAPI/MySQL service, deployment scripts, schema, and cloud audio resources.
- `hitrise-deploy/` - deployment examples and verification notes.
- `hitrise-docs/` - Hitrise project planning documents.
- `工程文件/` - product and protocol specification documents.
- `界面文件/` - UI reference prototypes and design assets.

## Safety Notes

Runtime secrets, database backups, APK outputs, local Android properties, and the Smart sensor ball reference project are intentionally excluded by `.gitignore`.
