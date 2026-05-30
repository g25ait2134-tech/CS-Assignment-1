# RSA Encryption and Signature Demo Lab

This Java lab demonstrates secure RSA usage, vulnerable RSA/signature scenarios, and a bounded local availability-load demo. The dashboard is available at:

```text
http://localhost:8090
```

## Dashboard Scrollback

The dashboard keeps up to 5,000 events in the browser and renders them in a scrollable log pane. During the DDOS/availability demo, use the `Follow Live` button:

- Active: the log pane stays pinned to the newest event.
- Paused Scroll: you can scroll upward and inspect earlier events.

The dashboard also requests up to 5,000 existing lines from `/logs/events.jsonl` when the page loads. You can raise or lower this with:

```yaml
DASHBOARD_INITIAL_EVENTS: "5000"
```

Old metrics can reappear after restart because Docker keeps the `demo-logs` named volume. To clear them, click `Reset Dashboard` in the top header or `Clear Metrics` in the log panel or stop the stack with volumes:

```bash
docker compose -f docker-compose.ddos.yml down -v
```

Use the matching compose file for the secure or attack stack if that is the one you are running.

## Code Walkthrough PDF

The detailed explanation is available at:

```text
docs/RSA_Demo_Code_Guide.pdf
```

## Secure Demo

```bash
docker compose -f docker-compose.secure.yml up --build secure-app dashboard
docker compose -f docker-compose.secure.yml --profile demo run --rm normal-demo
docker compose -f docker-compose.secure.yml --profile demo run --rm secure-rejection-demo
```

The rejection demo sends invalid signatures and invalid tokens to the secure app. In the dashboard, look for:

- `secure_reject_wrong_payload_signature`
- `secure_reject_corrupt_signature`
- `secure_reject_tampered_token_payload`
- `secure_reject_unsigned_alg_none_token`
- `secure_reject_malformed_token`
- server-side `token_verify_reject`

## Attack Demo

```bash
docker compose -f docker-compose.attack.yml up --build secure-app vulnerable-app dashboard
docker compose -f docker-compose.attack.yml --profile attack run --rm secure-rejection-demo
docker compose -f docker-compose.attack.yml --profile attack run --rm signature-attack-demo
docker compose -f docker-compose.attack.yml --profile attack run --rm weak-rsa-demo
```

## Bounded DDOS / Availability Demo

```bash
docker compose -f docker-compose.ddos.yml up --build secure-app dashboard
docker compose -f docker-compose.ddos.yml --profile ddos run --rm bounded-load-demo
```

## Toy RSA Methodology

The toy RSA demo exists only in vulnerable mode. It uses tiny fixed primes, publishes `n`, `e`, and a ciphertext, and the attacker client factors `n`, reconstructs `d`, and decrypts. This is a teaching model for bad key size and textbook RSA. The secure mitigation path uses RSA-OAEP-SHA256 and RSASSA-PSS-SHA256 with 2048+ bit RSA.
