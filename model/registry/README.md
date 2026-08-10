# Local model registry

This directory records immutable artifact and dataset hashes. `candidate` does not mean deployed.

```bash
python scripts/manage_local_model_registry.py verify
python scripts/manage_local_model_registry.py retention-plan
```

Promotion and rollback only update `model/deployments/local-deployment.json`; they never edit Android,
backend or Next.js runtime configuration. Promotion requires a registry entry with
`deployment_eligible=true` and an exact `--approve <model-id>` argument.

Retention is non-destructive by default. `quarantine` is available only for an archived, unreferenced
entry whose registry policy has `retain=false`, and requires an exact approval argument.
