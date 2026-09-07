# 04 — Payments

## Business behaviour

Orders are charged through a **payment gateway** abstraction. In this build the
gateway is simulated but the behaviour around it is production-shaped:

- The charge happens **inside the order transaction**: a failed charge rolls
  back the order and the stock deduction (no order = no charge).
- The gateway is wrapped in a resilience stack so outages degrade gracefully
  instead of hanging (see "Failure semantics" below).
- The API never sees or stores card data - the gateway returns a transaction
  reference and the `payments` table stores only `method`, `status`, `amount`,
  `transactionId` and `paymentDate`. There is no PAN/CVV/cardholder column
  anywhere (enforced by a schema test).

## Payment methods (`paymentMethod`)
`CREDIT_CARD`, `DEBIT_CARD`, `PAYPAL`, `BANK_TRANSFER`.
Currently every order charges as `CREDIT_CARD` through the simulated gateway.

## Payment statuses (`status`)
`PENDING` → `PROCESSED` (success) | `FAILED` (declined/error) | `REFUNDED` (future use).

- On order placement the charge succeeds or the whole order rolls back, so a
  persisted payment is `PROCESSED` with a `transactionId` (`sim-tx-...`) and a
  `paymentDate`.
- `PaymentFailedException` is surfaced to the API as `502 PAYMENT_FAILED` with a
  hint to retry using the same `Idempotency-Key` (retries never double-charge).

## Failure semantics (what integrators should expect)

| Situation | What the caller sees |
|---|---|
| Gateway declines (simulated ~10% random) | Retried with exponential backoff (3 attempts); if still failing → `502 PAYMENT_FAILED`, order rolled back |
| Gateway is slow beyond the breaker threshold | Circuit opens → subsequent calls fail fast as `502` instead of hanging |
| Circuit open (provider down) | Calls fail fast; recovery is probed automatically (half-open) |
| Concurrent writes on the same product | Optimistic locking + retries; rare persistent conflict → `409 CONCURRENT_MODIFICATION` |

Key point: **a `502` from order placement never leaves a half-made order or a
deducted stock level** - transactions guarantee it, and the idempotency key
makes retries safe.

## Notes for real-world adoption
Swap `SimulatedPaymentGateway` for a real PSP implementation behind the same
`PaymentGateway` interface. The resilience stack (bulkhead → circuit breaker →
retry) and the no-card-data rule keep the integration point small and PCI scope
minimal. Test doubles already let CI run "always succeed" and "always fail"
payment scenarios.

## Observability
Payment gateway health is visible through circuit-breaker metrics and the
payment SLO/alert rules (see `08-observability-health-monitoring.md` and
`docs/slo/order-api-slo.md`).
