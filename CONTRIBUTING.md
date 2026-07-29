# Contributing to a2at-engine-java

Thank you for your interest in contributing! This document covers the contribution process and coding standards.

## Prerequisites

- JDK 17+
- Maven 3.6+
- Git

## Development Setup

```bash
git clone git@github.com:Zhoujie628/a2at-engine-java.git
cd a2at-engine-java
mvn -o clean compile
mvn -o test
```

## Project Structure

```
a2at-engine-java/
|-- a2at-engine/          SDK engine module
|   +-- src/main/java/com/openan/a2at/engine/
|       |-- client/       A2A transport, auth, extensions (package-private internals)
|       |-- control/      User-facing: ControlPoint, EventCallback, EventType
|       |-- core/         Internal: WorkflowExecutor, ContextBuilder (package-private)
|       |-- model/        Data models
|       |-- registry/     LoadPsop, RegistryClient
|       +-- runner/       ExecutePsop (entry point)
|-- samples/              Demo applications
|-- docs/                 Documentation
|-- pom.xml               Parent POM (reactor)
```

## Coding Standards

### Java

- Java 17 language features (records, sealed, switch expressions)
- Methods should not exceed 50 lines; extract subroutines
- No raw `Object` types where a specific type exists
- Suppress warnings only when unavoidable; prefer type-safe alternatives
- Package-private for internal classes; `public` only for user-facing API
- All public methods must have Javadoc

### File Encoding

- Source files: UTF-8
- No BOM in source files
- Maven `sourceEncoding` is UTF-8 in the parent POM

### License Headers

Every Java file must start with the Apache 2.0 license header:

```java
/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */
```

### Naming

- `PascalCase` for classes, `camelCase` for methods/variables
- `UPPER_SNAKE_CASE` for constants
- Interface names: nouns or adjectives (`WorkflowEngineClient`, `AuthProvider`)
- Builder classes: nested `Builder` static class

### Logging

- SLF4J Logger per class
- INFO: key lifecycle events, auth success/failure, negotiation rounds
- DEBUG: full message content, prompt text, response details
- WARN: recoverable failures, fallbacks
- ERROR: auth failures, agent call failures
- Dedicated `PROTOCOL` logger for protocol-level request/response dumps
- No log truncation in demo/samples; full messages should be visible

### Testing

- JUnit 5
- Unit tests for all public methods
- Integration tests for end-to-end workflows
- Run tests: `mvn -o test`

## Commit Process

1. Fork the repository and create a feature branch
2. Write code following the standards above
3. Add/update tests
4. Run `mvn -o test` and ensure all tests pass
5. Add DCO signoff to your commit:
   ```
   Signed-off-by: Your Name <your.email@example.com>
   ```
6. Use conventional commit messages:
    - `feat:` new feature
    - `fix:` bug fix
    - `docs:` documentation
    - `refactor:` code restructuring
    - `test:` test additions
    - `chore:` build/config

## Pull Request

1. Ensure your branch is up to date with `main`
2. Squash unrelated commits
3. Write a clear PR description with:
    - What changed and why
    - Any breaking changes
    - Test results
4. Link related issues

## Issue Reporting

- Use GitHub Issues
- Include: Java version, Maven version, error log, reproduction steps
- For protocol issues: include the `PROTOCOL` logger output
