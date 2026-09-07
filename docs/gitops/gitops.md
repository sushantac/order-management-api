# GitOps deployment model (PR #34)

One repo, many environment branches; ArgoCD is the deployer.

| Branch   | Cluster env | Overlay       |
|----------|-------------|---------------|
| develop  | CI only     | (tests)       |
| test     | test        | overlays/test |
| uat      | uat         | overlays/uat  |
| staging  | staging     | overlays/staging |
| main     | prod        | overlays/prod |

Promotion flow (all in git, auditable, revertable):
1. CI (`.github/workflows/ci.yml`) runs the full suite on every PR to develop.
2. A promotion (`.github/workflows/promote.yml` or manually) bumps the image
   tag in the target overlay and merges to the env branch.
3. ArgoCD watches the env branch (`k8s/argocd/order-api.yaml`) and syncs the
   cluster to the committed state.
4. Secrets travel as **SealedSecrets** (encrypted in git, decrypted only by the
   cluster controller) - never plaintext.

Progressive delivery = promote one environment at a time, watch SLOs
(docs/slo) and alert rules before the next hop. Rollback = revert the commit;
ArgoCD converges back automatically.
