#!/usr/bin/env sh
# Runs the server in a loop. Before every boot, it applies the update that Core
# left ready in .git-driven/staging/. Run it FROM INSIDE the server folder.
#
#   scripts/start.sh               # loop: apply -> start -> (stopped) -> apply -> start ...
#   scripts/start.sh --apply-only  # only apply and exit (handy with systemd: ExecStartPre)
#
# Variables: SERVER_JAR (folia.jar), MEMORY (4G), JAVA (java), JAVA_ARGS, RESTART_DELAY (5)
set -eu

SERVER_JAR="${SERVER_JAR:-folia.jar}"
MEMORY="${MEMORY:-4G}"
JAVA="${JAVA:-java}"
JAVA_ARGS="${JAVA_ARGS:-}"
RESTART_DELAY="${RESTART_DELAY:-5}"
GD=".git-driven"

apply_update() {
  [ -f "$GD/staging/READY" ] || return 0
  commit=$(head -n 1 "$GD/staging/COMMIT")
  echo "[git-driven] Applying $commit"

  mkdir -p plugins
  # Remove the jars Core installed last time (plugins deleted from the repo disappear).
  if [ -f "$GD/managed-plugins.txt" ]; then
    while IFS= read -r jar; do
      [ -n "$jar" ] && rm -f "plugins/$jar"
    done < "$GD/managed-plugins.txt"
  fi
  : > "$GD/managed-plugins.txt"
  for jar in "$GD"/staging/plugins/*.jar; do
    [ -e "$jar" ] || continue
    cp -f "$jar" plugins/
    basename "$jar" >> "$GD/managed-plugins.txt"
  done

  # Versioned configs (the repo's server/ folder) over the server root.
  if [ -d "$GD/staging/files" ]; then
    cp -R "$GD/staging/files/." ./
  fi

  cp -f "$GD/staging/CHANGES.txt" "$GD/last-changes.txt" 2>/dev/null || : > "$GD/last-changes.txt"
  printf '%s\n' "$commit" > "$GD/deployed-commit"
  rm -rf "$GD/staging"
  echo "[git-driven] $commit applied."
}

if [ "${1:-}" = "--apply-only" ]; then
  apply_update
  exit 0
fi

while :; do
  apply_update
  # shellcheck disable=SC2086 # JAVA_ARGS is a list of flags
  "$JAVA" -Xms"$MEMORY" -Xmx"$MEMORY" $JAVA_ARGS -jar "$SERVER_JAR" --nogui || true
  echo "[git-driven] Server stopped. Starting again in ${RESTART_DELAY}s (Ctrl+C to quit)..."
  sleep "$RESTART_DELAY"
done
