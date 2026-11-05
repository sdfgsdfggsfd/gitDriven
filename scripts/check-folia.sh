#!/usr/bin/env sh
# Catches the most common Folia mistakes before the merge. Runs in CI and locally:
#   sh scripts/check-folia.sh
set -eu
fail=0

# 1. The Bukkit scheduler doesn't exist on Folia (it throws UnsupportedOperationException).
#    (comment lines are ignored)
hits=$(grep -rnE 'Bukkit\.getScheduler\(\)|getServer\(\)\.getScheduler\(\)|BukkitRunnable|BukkitScheduler' \
         plugins/*/src/main/java 2>/dev/null \
       | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*)' || true)
if [ -n "$hits" ]; then
  echo "$hits"
  echo "::error::Use the Folia schedulers (entity.getScheduler(), Bukkit.getRegionScheduler(), getGlobalRegionScheduler(), getAsyncScheduler()) — see AGENTS.md."
  fail=1
fi

# 2. Every plugin must declare itself compatible, otherwise Folia won't even load it.
for yml in plugins/*/src/main/resources/plugin.yml; do
  if ! grep -qE '^folia-supported:[[:space:]]*true' "$yml"; then
    echo "::error file=$yml::Missing 'folia-supported: true'."
    fail=1
  fi
done

[ "$fail" -eq 0 ] && echo "check-folia: ok"
exit "$fail"
