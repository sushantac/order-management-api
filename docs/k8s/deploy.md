# Deploying order-management-api to Kubernetes (PR #33)

## Build & push

docker build -t ghcr.io/sushantac/order-management-api:<env> .
docker push ghcr.io/sushantac/order-management-api:<env>

## Kustomize overlays

Environment promotion uses the same manifests with a per-env overlay:

    kubectl apply -k k8s/overlays/dev
    kubectl apply -k k8s/overlays/test
    kubectl apply -k k8s/overlays/uat
    kubectl apply -k k8s/overlays/staging
    kubectl apply -k k8s/overlays/prod

Each overlay sets: name prefix (dev-), replicas, image tag, active Spring profile
(lower envs = dev profile, staging/prod = prod profile).

## Secrets (Sealed Secrets)

Commit ENCRYPTED secrets only - a SealedSecret decrypts to a normal Secret in the
target cluster via the controller's key pair:

    kubectl create secret generic order-api-secrets --dry-run=client -o yaml \
      | kubeseal --format yaml --scope cluster-wide \
      > k8s/overlays/prod/sealed-db-secret.yaml

Then add `sealed-db-secret.yaml` to the prod overlay resources. Never commit the
plaintext dev secret (see k8s/samples/ - example only).

## Probes

The deployment uses the Boot probe endpoints: `/actuator/health/liveness` and
`/actuator/health/readiness` (enabled via `management.endpoint.health.probes`),
plus a startup probe so slow cold starts never kill the pod.
