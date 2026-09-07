# Contract testing with Pact (PR #34)

Pact gives consumers (storefront, mobile) a recorded *contract* and verifies the
provider against it in CI - so the API can never silently break a client.

- **Consumer side**: the client records the interactions it expects
  (`docs/contracts/order-api.pact.json` is a manually-written example).
- **Provider side**: a Pact verification test replays the interactions against
  the running provider (spring-cloud-contract/pact-jvm-provider) - we would add
  the pact provider dependency and point it at this file.
- **Pact Broker** stores versions + verification results so both sides know the
  current truth.

Run flow: consumer change -> publish pact -> provider CI verifies -> only then
can the provider deploy with the new behaviour. The JSON contract example above
shows the PII-masking rule too (emails are masked in responses, PR #27).
