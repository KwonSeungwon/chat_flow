# Contributing to ChatFlow

Thank you for your interest in contributing to ChatFlow! This document provides guidelines and information for contributors.

## 📋 Table of Contents
- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Git Flow Workflow](#git-flow-workflow)
- [Branch Naming Convention](#branch-naming-convention)
- [Commit Message Guidelines](#commit-message-guidelines)
- [Pull Request Process](#pull-request-process)
- [Code Style Guidelines](#code-style-guidelines)
- [Testing Requirements](#testing-requirements)

## 📜 Code of Conduct
By participating in this project, you agree to abide by our Code of Conduct. Please be respectful and constructive in all interactions.

## 🚀 Getting Started

### Prerequisites
- Java 21+
- Node.js 23.5+
- Docker & Docker Compose
- Git

### Setup Development Environment
```bash
# Clone the repository
git clone https://github.com/your-username/chatflow.git
cd chatflow

# Start infrastructure services
docker-compose up -d kafka redis elasticsearch postgresql

# Backend setup
./gradlew build

# Frontend setup
cd frontend
npm install
npm run dev
```

## 🌊 Git Flow Workflow

We use Git Flow branching strategy with the following branch types:

### Main Branches
- **`main`**: Production-ready code
- **`develop`**: Integration branch for features

### Supporting Branches
- **`feature/*`**: New features
- **`release/*`**: Release preparation
- **`hotfix/*`**: Critical production fixes

### Branch Workflow
1. **New Feature**: `develop` → `feature/feature-name` → `develop`
2. **Release**: `develop` → `release/v1.0.0` → `main` + `develop`
3. **Hotfix**: `main` → `hotfix/fix-name` → `main` + `develop`

## 🏷️ Branch Naming Convention

### Feature Branches
- `feature/backend-microservices`
- `feature/frontend-vue3-ui`
- `feature/ai-chat-summarization`
- `feature/elasticsearch-search`
- `feature/websocket-realtime-chat`
- `feature/docker-containerization`
- `feature/kubernetes-deployment`

### Release Branches
- `release/v1.0.0`
- `release/v1.1.0`

### Hotfix Branches
- `hotfix/websocket-connection-fix`
- `hotfix/security-vulnerability-patch`

## 📝 Commit Message Guidelines

We follow [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

### Types
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes
- `refactor`: Code refactoring
- `perf`: Performance improvements
- `test`: Adding or modifying tests
- `chore`: Build process or auxiliary tool changes

### Examples
```bash
feat(chat): add real-time message broadcasting
fix(auth): resolve JWT token validation issue
docs(readme): update installation instructions
```

## 🔀 Pull Request Process

1. **Create Feature Branch**
   ```bash
   git checkout develop
   git pull origin develop
   git checkout -b feature/your-feature-name
   ```

2. **Make Changes**
   - Write clean, well-documented code
   - Add/update tests
   - Update documentation if needed

3. **Test Locally**
   ```bash
   # Backend tests
   ./gradlew test
   
   # Frontend tests
   cd frontend
   npm run lint
   npm run type-check
   npm run build
   ```

4. **Commit Changes**
   ```bash
   git add .
   git commit -m "feat(scope): description"
   ```

5. **Push and Create PR**
   ```bash
   git push origin feature/your-feature-name
   ```
   Then create a Pull Request on GitHub.

6. **PR Review Process**
   - Automated CI/CD checks must pass
   - Code review by at least one maintainer
   - Address any requested changes
   - Squash merge when approved

## 🎨 Code Style Guidelines

### Backend (Java)
- Use Java 21 features where appropriate
- Follow Spring Boot conventions
- Use Lombok for reducing boilerplate
- Write comprehensive JavaDoc for public APIs

### Frontend (TypeScript/Vue)
- Use TypeScript for type safety
- Follow Vue 3 Composition API patterns
- Use ESLint configuration provided
- Follow Bootstrap 5 component patterns

### General Guidelines
- Write self-documenting code
- Use meaningful variable and function names
- Keep functions small and focused
- Add comments for complex business logic

## 🧪 Testing Requirements

### Backend Testing
- Unit tests for all service classes
- Integration tests for APIs
- Minimum 80% code coverage

### Frontend Testing
- Component unit tests (when applicable)
- E2E tests for critical user flows
- Cross-browser compatibility

### Test Commands
```bash
# Backend
./gradlew test

# Frontend
cd frontend
npm test
```

## 🔧 Development Tips

### Running Services Individually
```bash
# Gateway Service
./gradlew :gateway-service:bootRun

# Chat Service
./gradlew :chat-service:bootRun

# AI Summary Service
./gradlew :ai-summary-service:bootRun

# Search Service
./gradlew :search-service:bootRun
```

### Frontend Development
```bash
cd frontend

# Web development
npm run dev

# Desktop app development
npm run electron:dev
```

## 📞 Getting Help

- **Issues**: Create an issue on GitHub
- **Discussions**: Use GitHub Discussions for questions
- **Documentation**: Check the README and docs/ folder

## 🎉 Recognition

Contributors will be recognized in:
- README.md contributors section
- Release notes
- Annual contributor spotlight

Thank you for contributing to ChatFlow! 🚀