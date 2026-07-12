# Security Policy

This project handles last-mile courier workflows (stores, orders,
deliveries, proof-of-delivery, settlement). Treat vulnerabilities as
potentially high impact even when the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real merchant, customer or courier data exposure
- authorization bypass
- Courier Governor bypass
- audit-ledger tampering
- proof-of-delivery forgery or suppression
- over-disclosure in reports or exports
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on delivery data, policy enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real merchant/customer/delivery data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
