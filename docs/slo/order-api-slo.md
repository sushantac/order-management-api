# Order Management API — SLOs (PR #32)

Service Level Objectives for the read/write API paths. SLIs are measured from
Prometheus metrics (see docs/monitoring/prometheus/alerts.yml for the alert rules).

| SLI (metric)                    | SLO (30d) | Alert if (5m window)             |
|---------------------------------|-----------|----------------------------------|
| Order placement success rate    | >= 99.5%  | < 99.0% `order_place` exceptions |
| Order placement latency p95     | < 500 ms  | p95 > 1 s (5m)                   |
| Product read latency p95        | < 300 ms  | p95 > 750 ms (5m)                |
| API availability (HTTP 5xx)     | >= 99.9%  | 5xx ratio > 0.1% (5m)            |
| Payment gateway circuit opens   | < 1 / day | breaker OPEN > 2 min             |

Error budget policy: each SLO burns 1% budget per day of violation; two
consecutive burn days pause deploys until the budget recovers.
