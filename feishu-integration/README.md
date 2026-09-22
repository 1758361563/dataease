# feishu-integration

Independent Node service for DataEase card screenshots and Feishu delivery.

It lives in this DataEase git repo as a **separate directory**: own `package.json`, own runtime, own deploy. It is not a Java module and is not mixed into `core-backend`.

The previous working copy at `test/feishu-integration/` was left in place as a backup until the in-repo path is confirmed. Continue 2B work here.

## Runtime files that must never be committed

- `.env`
- `node_modules/`
- `tmp/` (PNG, reports)

Set `FEISHU_INTERNAL_TOKEN` for `POST /v1/deliveries`. Bind address is `127.0.0.1`. Browsers should call DataEase `/company/bi/feishu/...`, not this port.

## Run and restart

Local process: `node src/index.mjs`. A database error during boot is written to `log/service-events.jsonl` and the process exits non-zero. It does not mark a delivery successful.

`docker-compose.yml` is the test stack: `restart: unless-stopped`, dry-run forced on, secrets only from `.env`, logs bind-mounted to `./log` so they survive container replacement. It joins the existing DataEase network and does not add another database or queue. Do not deploy this file to the tower from this change.

`src/seed-dev-config.mjs` is a one-off Phase 2C test tool. Official config rows are written only by the DataEase BFF.
