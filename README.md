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

## Secure Demo

```bash
docker compose -f docker-compose.secure.yml up --build secure-app dashboard
docker compose -f docker-compose.secure.yml --profile demo run --rm normal-demo
```

## Attack Demo

```bash
docker compose -f docker-compose.attack.yml up --build secure-app vulnerable-app dashboard
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
