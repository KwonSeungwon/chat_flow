# Repository Guidelines

## Project Structure & Module Organization

ChatFlow is a multi-service application. Backend services are Gradle subprojects in `settings.gradle`: `chat-service`, `ai-summary-service`, `search-service`, `gateway-service`, and shared code in `common`. Java source lives under each module's `src/main/java`; backend tests live under `src/test/java`.

The client is a Flutter app in `frontend/`. App code is organized by `lib/core`, `lib/features`, and `lib/shared`; tests are in `frontend/test`. Infrastructure files live in `docker-compose.*.yml`, `elasticsearch/`, `helm/`, `k8s/`, `monitoring/`, and `scripts/`.

## Build, Test, and Development Commands

- `./gradlew build`: compile all backend modules, run tests, Checkstyle, and packaging.
- `./gradlew test`: run backend JUnit tests only.
- `./gradlew :chat-service:test`: run tests for one service.
- `./gradlew jacocoTestReport`: generate JaCoCo reports.
- `docker compose -f docker-compose.local.yml up -d`: start local services.
- `cd frontend && flutter pub get`: install Flutter dependencies.
- `cd frontend && flutter analyze`: run Dart/Flutter static analysis.
- `cd frontend && flutter test`: run Flutter unit and widget tests.
- `cd frontend && flutter run -d chrome`: run the web client locally.

## Coding Style & Naming Conventions

Backend code uses Java with Spring Boot conventions. Gradle sets Java source and target compatibility to 17; CI uses Temurin 21. Use package names under `com.chatflow.<service>`, PascalCase classes, camelCase methods and fields, and `*Test` test classes. Checkstyle is configured in `config/checkstyle/checkstyle.xml`.

Flutter code follows `package:flutter_lints/flutter.yaml` from `frontend/analysis_options.yaml`. Use snake_case file names, PascalCase widgets/classes, and Riverpod/provider names that match the feature they serve.

## Testing Guidelines

Backend tests use JUnit 5, Spring Boot Test, and Testcontainers for integration dependencies. Keep tests near the package they cover. Run `./gradlew build` before backend PRs.

Frontend tests use `flutter_test`; place feature tests under `frontend/test/features/...` and shared tests under `frontend/test/shared/...`. Run `flutter analyze` and `flutter test` before UI changes.

## Commit & Pull Request Guidelines

Recent history follows Conventional Commits with scopes, for example `feat(chat-service): ...` and `refactor(chat-service): ...`. Use concise imperative subjects and include the affected module when useful.

Open PRs from `feature/*`, `refactor/*`, `hotfix/*`, or release branches into `develop` unless targeting production fixes. Include a short description, linked issue when applicable, test results, and screenshots for visible Flutter UI changes. Note config or deployment impact explicitly.

## Security & Configuration Tips

Do not commit real secrets. Use `.env.example` and `.env.prod.example` as templates, and keep local credentials in ignored environment files. Treat Firebase, JWT, database, Kafka, Elasticsearch, and AI provider settings as sensitive configuration.
