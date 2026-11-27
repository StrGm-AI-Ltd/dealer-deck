# DEALER + DECK

**Infrastructure for agent fleets.** DEALER is one OpenAI-compatible endpoint that routes agent sessions
across a DECK of LLM servers (local hardware and cloud models) by client priority, availability and cost.
When local capacity is taken by a more important client, DEALER evicts lower-priority sessions between
their requests and, where the client's policy allows it, moves them to the cloud.

> *ACE tells you which cards win; DEALER deals them from the DECK.*

v0.5 was built at a hackathon. The local DECK server is a **simulated stub**; the external DECK server is
**real** and runs on **Nebius Token Factory**.

## How DEALER + DECK uses Nebius Token Factory

- **Nebius Token Factory is the provider behind the external DECK server** (the `deck-openai`
  module). It serves `nebius/…` profiles, here `nebius/Qwen/Qwen3-30B-A3B-Instruct-2507` with a 262k
  context window, through Nebius's OpenAI-compatible API (`https://api.tokenfactory.nebius.com/v1/`).
- **It's the overflow capacity behind local hardware.** In the demo, when a priority-80 client needs the
  local hardware, DEALER evicts two priority-50 agents. Agent A, whose client allows external routing, is
  re-placed on Nebius and keeps working on real tokens. Agent B is local-only, so it waits for the
  hardware instead. The cloud is used only where the operator's policy allows it.
- **Real usage and cost.** The external DECK server forces `stream_options.include_usage`, records prompt,
  cached and completion tokens per lease, and prices them with the model's per-1M-token prices. DEALER
  shows tokens and cost per session in the DEALER UI.
- **Budgets for expensive models.** Each Nebius model can have an instance budget (`max-leases`): at most
  N sessions on it at once. Over budget, DECK refuses the lease with the leases in the way, and DEALER
  queues or evicts by client priority, exactly as it does for local hardware.
- **Credentials.** In v0.5, DEALER holds `NEBIUS_API_KEY` and sends it with each request; the external
  DECK server forwards it and stores nothing. Locking provider credentials inside DECK is planned for v2.

## Architecture

```
  agents / OpenAI SDKs                             DEALER UI (Vaadin, :8090)
          │  OpenAI-compatible, streaming                │ metrics REST APIs
          ▼                                              ▼
  ┌──────────────────────────── DEALER (:8080) ─────────────────────────────┐
  │ API keys + client priority · sessions (X-Session-Id) · router queue      │
  │ placement (local first) · eviction between requests · tokens + cost      │
  └───────────────┬───────────────────────────────────────┬─────────────────┘
                  │ DECK API (model profiles, leases, chat, metrics; DECK key)
                  ▼                                       ▼
     DECK stub (:8081, local)                 DECK external (:8082)
     3 profiles over 2 simulated workers      nebius/… profiles, lease budgets
     small-a, small-b, large                          │
                                                      ▼
                                           Nebius Token Factory
```

- **DEALER** knows nothing about hardware. It routes and evicts in profile and lease terms only.
- **DECK** is one interface with two implementations: the external server (Nebius in v0.5) and the
  local server (a stub in v0.5; real BC-250 boards and a Mac in v1). The external implementation talks to any
  OpenAI-compatible provider, so it can also front a server on your own hardware, such as oMLX, which it reports
  as `local`. A DECK server publishes capacity-aware
  model profiles (readiness, loading time, limitations, context windows, prefill and decode speeds by context
  size, quantization, prices, budgets) and grants leases.
- **The DEALER UI** is a separate app that reads both services' metrics REST APIs. The servers define no UI.

## API first

Both APIs are defined in OpenAPI 3.0 before any code, in one place: the `api` module
([`dealer.yaml`](api/src/main/resources/static/openapi/dealer.yaml),
[`deck.yaml`](api/src/main/resources/static/openapi/deck.yaml) and the shared OpenAI chat schemas in
[`common.yaml`](api/src/main/resources/static/openapi/common.yaml)).

- The build validates the documents and generates the Spring server interfaces and models that DEALER
  and DECK implement, plus the Spring HTTP-interface clients that DEALER (to call DECK) and the DEALER
  UI (to read metrics) use. Nothing is hand-written twice.
- Each service shows its document in Swagger UI: DEALER at <http://localhost:8080/swagger-ui.html>, the
  DECK servers at `:8081` and `:8082`.
- One exception: the streaming chat endpoints are fully described in the documents, but the code that
  streams server-sent events is hand-written (over the generated models), because the generated Spring
  interfaces return a single object.

## Quick start

Requirements: Java 21+, and a Nebius Token Factory API key.

```bash
export NEBIUS_API_KEY=...     # never commit it
./demo.sh
```

`demo.sh` builds the project, starts the stub DECK, the Nebius DECK, DEALER and the DEALER UI, and runs
the scenario. Open <http://localhost:8090> to watch it. The services keep running afterwards; Ctrl-C
stops them.

**The scenario** (about 80 seconds, `scripts/scenario.sh`):
1. `agent-a` (priority 50, external routing allowed) and `agent-b` (priority 50, local only) loop on the
   virtual model `auto-32k`. DEALER places them on the stub's `small-a` and `small-b`.
2. `vip` (priority 80, local only) asks for `large` by name. Only the stub offers it, and it needs both
   workers. DEALER evicts both agents between requests and reserves the capacity for `vip`, so neither
   agent can take it back.
3. `agent-a` is re-placed on Nebius Token Factory and continues on real tokens. `agent-b` waits.
4. `vip` stops. After its lease idles out, `agent-b` resumes on the stub.

Talk to DEALER yourself:

```bash
curl -N http://localhost:8080/v1/chat/completions \
  -H 'Authorization: Bearer key-agent-a' -H 'X-Session-Id: my-session' -H 'Content-Type: application/json' \
  -d '{"model":"auto-32k","stream":true,"messages":[{"role":"user","content":"Hello"}]}'
```

Any OpenAI SDK works the same way: set the base URL to `http://localhost:8080/v1`, the API key to a
client key, and send the `X-Session-Id` header.

## Build and run each service

```bash
./gradlew build                                   # validates the OpenAPI documents, generates code, builds everything
java -jar deck-stub/build/libs/deck-stub.jar                            # :8081
java -jar deck-openai/build/libs/deck-openai.jar                        # :8082
java -jar dealer/build/libs/dealer.jar                                  # :8080 (needs NEBIUS_API_KEY for the Nebius path)
./gradlew :dealer-ui:bootRun                                            # :8090
```

| Module | What it is |
|---|---|
| `api` | The OpenAPI documents (the single source of truth) and the code generated from them |
| `dealer` | The router (Spring Boot 4, Java 21) |
| `deck` | What every DECK server shares (`ai.strgm.deck`): the DECK API's web layer, the `DeckServer` interface and the lease store |
| `deck-openai` | The DECK server backed by an OpenAI-compatible provider: Nebius by default, or a local server such as oMLX |
| `deck-stub` | The simulated local DECK server |
| `dealer-ui` | The DEALER UI (Vaadin 25) |

## Configuration

- **DEALER** (`dealer/src/main/resources/application.yml`):
  - `dealer.clients`: name, API key, priority (1–100; only client priority evicts) and
    `external-routing` (off by default: may this client's sessions run on external servers?);
  - `dealer.servers`: each DECK server's URL, egress class (`local` or `external`, set by the operator,
    not by the server), DECK key and, for external servers, the environment variable holding the
    provider credential;
  - `dealer.timers`:
    - the minimum lease time before a lease can be evicted;
    - the idle time after which a lease is released;
    - how long a request waits for its lease to be ready, and how often it asks DECK meanwhile;
    - how often idle leases are released and the queue is dispatched;
    - how often DEALER refreshes the DECK servers' model profiles;
  - `dealer.timeouts`: the connect and read timeouts of DEALER's calls to DECK, and of a forwarded chat
    request, whose time limit covers its whole stream;
  - `dealer.eviction-history`: how many eviction events `/metrics/state` keeps.
- **DECK** (`application.yml` in `deck-stub` and `deck-openai`, and the shared `deck-defaults.yml`):
  - the DECK key;
  - how many recent leases `/metrics/state` reports;
  - for the stub: its time factor, and how long a chat request waits for a lease that's still loading;
  - under `deck.upstream`: the provider (Nebius by default), its models with their context windows,
    prices, quantization and `max-leases` budgets, and the timeouts of a chat request to the provider.

  To serve a local oMLX model instead, for example:

  ```bash
  DECK_UPSTREAM_PROVIDER=omlx DECK_UPSTREAM_IMPLEMENTATION=local DECK_UPSTREAM_BASEURL=http://127.0.0.1:9191/v1/ \
  DECK_UPSTREAM_MODELS_0_MODEL=Qwen3.8-27B-graft DECK_UPSTREAM_MODELS_0_CONTEXTWINDOW=65536 DECK_UPSTREAM_MODELS_0_MAXLEASES=1 \
    java -jar deck-openai/build/libs/deck-openai.jar --server.port=8083 --deck.server-name=local
  ```

- **DEALER UI** (`dealer-ui/src/main/resources/application.yml`): DEALER's and each DECK server's URL and key,
  how often the page refreshes, and the timeouts of its calls.

Every timeout, interval and limit has its default in these files, and can be overridden like any other key,
e.g. `--dealer.timers.readiness-timeout-seconds=120`, without a rebuild.

All keys in the configuration files are demo values. Override them with environment variables
(`DECK_STUB_KEY`, `DECK_NEBIUS_KEY`, `DEALER_METRICS_KEY`) outside a demo.

## What the stub simulates

Two workers and three profiles: `small-a` and `small-b` (one worker each, 32k context) and `large`
(both workers, 131k context). Leasing `large` is blocked by both small leases, and switching between
them takes a simulated load time. Prefill and decode speeds fall as the context grows; a request streams at
the speeds its profile advertises for the prompt's size. The text is canned:
the stub demonstrates scheduling, not model quality.

## Prior art, and what's different

Each piece exists somewhere: LiteLLM routes across providers with budgets and fallbacks; llm-d schedules
inference on Kubernetes; Pi's router extensions pick models per request; NVIDIA NeMo Switchyard routes
between models. DEALER + DECK combines them for **agent fleets**: sticky sessions on leases, per-client
priority that can evict between requests, a capacity-aware contract between the router and servers that
own hardware, and a per-client policy deciding who may overflow to the cloud.

## Status and next steps

- **v0.5 (this release):** everything above.
- **v1:** the real local DECK server on BC-250 boards and a Mac, OAuth2 and a client database, full lease
  rules, load shedding, a quality-aware `auto` rule using ACE results.
- **v2:** provider credentials locked inside DECK, mid-session switching, mid-stream eviction.

The full design, with its decision record, is in the
[Design wiki page](https://github.com/StrGm-AI-Ltd/dealer-deck/wiki/Design).

## License

Apache 2.0
