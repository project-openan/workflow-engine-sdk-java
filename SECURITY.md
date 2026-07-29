# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a Vulnerability

If you discover a security vulnerability in this project, please report it
responsibly.

**Do NOT open a public GitHub issue for security vulnerabilities.**

Instead, please email: **security@openan.com**

Include the following in your report:

- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if any)

We will acknowledge receipt within 48 hours and provide a fix or mitigation
within 90 days. Valid reports will be credited in the release notes.

## Security Features

- **Credential encryption**: Passwords in credential config files support
  AES-256-GCM encryption via the `A2AT_CRED_KEY` environment variable.
  See [Integration Guide](docs/en/INTEGRATION_GUIDE.md#521-credential-encryption-and-key-management).

- **Custom AuthProvider**: For environments requiring external identity
  providers (SSO, OAuth2, etc.), implement `AuthProvider` to control
  authentication without storing credentials locally.
  See [Integration Guide](docs/en/INTEGRATION_GUIDE.md#53-custom-authentication-authprovider).

- **TLS/HTTPS**: All agent communication supports HTTPS with configurable
  certificate verification. Self-signed certificates are supported for
  development via `sslVerify(false)`.