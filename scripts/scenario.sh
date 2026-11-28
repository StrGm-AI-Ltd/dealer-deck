#!/usr/bin/env bash
# The v0.5 demo scenario against a running DEALER: priority eviction with policy-governed overflow to Nebius.
#  - agent-a (priority 50, external routing ON) and agent-b (priority 50, external routing OFF) loop on auto-32k;
#    they start on the stub's small-a and small-b profiles.
#  - vip (priority 80, local only) asks for `large` by name: only the stub offers it, and it needs both workers,
#    so DEALER evicts both agents between requests and reserves the capacity for vip.
#  - agent-a is re-placed on Nebius Token Factory; agent-b may not leave local hardware, so it waits.
#  - vip stops; after its lease idles out, agent-b resumes on the stub.
# VIP_MODEL is the model vip asks for: `large` (the stub's) by default; against a local oMLX DECK server, its
# model, e.g. omlx/Qwen3.8-27B-graft.
set -u
DEALER=${DEALER_URL:-http://localhost:8080}
DURATION=${DURATION:-80}
VIP_AT=${VIP_AT:-20}
THINK=${THINK:-2}
VIP_MODEL=${VIP_MODEL:-large}

PROMPTS=(
  "In two sentences, when should I prefer composition over inheritance?"
  "In two sentences, what is a race condition and how do I avoid one?"
  "In two sentences, why are pure functions easier to test?"
  "In two sentences, what does idempotent mean for an HTTP API?"
  "In two sentences, when is a database index a bad idea?"
)

auth_header() { # api-key: the one place the script writes how an API key is presented
  printf 'Authorization: Bearer %s' "$1"
}

ask() { # name key session model prompt
  local hdr text status server profile
  hdr=$(mktemp)
  text=$(curl -s -N -D "$hdr" -H "$(auth_header "$2")" -H "X-Session-Id: $3" -H 'Content-Type: application/json' \
    -d "{\"model\":\"$4\",\"stream\":true,\"max_tokens\":${MAX_TOKENS:-60},\"messages\":[{\"role\":\"user\",\"content\":\"$5\"}]}" \
    "$DEALER/v1/chat/completions" | python3 -c '
import sys, json
out = []
for line in sys.stdin:
    line = line.strip()
    if line.startswith("data: {"):
        chunk = json.loads(line[6:])
        for c in chunk.get("choices") or []:
            out.append((c.get("delta") or {}).get("content") or "")
    elif line.startswith("{"):
        out.append(json.loads(line).get("error", {}).get("message", line))
print("".join(out).replace("\n", " ").strip())')
  status=$(head -1 "$hdr" | awk '{print $2}')
  server=$(grep -i '^x-dealer-server:' "$hdr" | awk '{print $2}' | tr -d '\r')
  profile=$(grep -i '^x-dealer-profile:' "$hdr" | awk '{print $2}' | tr -d '\r')
  rm -f "$hdr"
  printf '%s %-9s %-4s %-50s %s\n' "$(date +%H:%M:%S)" "[$1]" "$status" "${server:--}/${profile:--}" "${text:0:110}"
}

agent() { # name key session seconds
  local end=$((SECONDS + $4)) i=0
  while [ $SECONDS -lt $end ]; do
    ask "$1" "$2" "$3" auto-32k "${PROMPTS[$((i % ${#PROMPTS[@]}))]}"
    i=$((i + 1))
    sleep "$THINK"
  done
}

echo "DEALER demo: agent-a and agent-b on auto-32k; vip asks for '$VIP_MODEL' at +${VIP_AT}s. Watch the DEALER UI."
agent agent-a key-agent-a a-1 "$DURATION" &
A=$!
sleep 0.3
agent agent-b key-agent-b b-1 "$DURATION" &
B=$!
sleep "$VIP_AT"
echo "------ vip (priority 80, local only) asks for '$VIP_MODEL': DEALER evicts both agents between requests ------"
ask vip key-vip vip-1 "$VIP_MODEL" "Summarise the design of a priority scheduler in two sentences."
ask vip key-vip vip-1 "$VIP_MODEL" "Name one risk of eviction in one sentence."
echo "------ vip is done; its lease idles out after the idle timeout, then agent-b resumes on the stub ------"
wait $A $B
echo "------ evictions and per-session usage ------"
curl -s -H "$(auth_header "${DEALER_METRICS_KEY:-metrics-key}")" "$DEALER/metrics/state" | python3 -c '
import sys, json
s = json.load(sys.stdin)
for e in s["evictions"]:
    print("evicted %s from %s/%s: %s" % (e["victim_client"], e["deck_server"], e["profile_id"], e["reason"]))
for x in s["sessions"]:
    print("%-8s %-6s on %s/%s: %d requests, %d+%d tokens, $%.6f" % (x["client"], x["session_id"], x.get("deck_server") or "-",
          x.get("profile_id") or "-", x["requests"], x["prompt_tokens"], x["completion_tokens"], x["cost_usd"]))'
