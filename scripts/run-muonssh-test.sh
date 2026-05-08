#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE_DIR="${MUONSSH_TEST_PROFILE:-/home/userk/muonssh-test-profile}"
PREFS_DIR="$PROFILE_DIR/.java-prefs"
LOG_DIR="$PROFILE_DIR/logs"
LOG_FILE="$LOG_DIR/muonssh-test.log"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT_FILE="$UNIT_DIR/muonssh-test.service"
SERVICE_NAME="muonssh-test.service"
ACTION="${1:-start}"

version() {
  sed -n '0,/<version>/{s:.*<version>\([^<]*\)</version>.*:\1:p}' "$ROOT_DIR/pom.xml" | head -n 1
}

jar_path() {
  printf '%s/muon-app/target/muonssh_%s.jar\n' "$ROOT_DIR" "$(version)"
}

build_jar() {
  if [ "${MUONSSH_SKIP_BUILD:-0}" = "1" ]; then
    return
  fi
  (cd "$ROOT_DIR" && mvn package -DskipTests -q)
}

write_unit() {
  local jar="$1"
  mkdir -p "$PROFILE_DIR" "$PREFS_DIR" "$LOG_DIR" "$UNIT_DIR"
  cat > "$UNIT_FILE" <<EOF
[Unit]
Description=MuonSSH test profile
After=graphical-session.target

[Service]
Type=simple
WorkingDirectory=$ROOT_DIR
Environment=DISPLAY=${DISPLAY:-:1}
Environment=XAUTHORITY=${XAUTHORITY:-}
Environment=WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-}
Environment=DBUS_SESSION_BUS_ADDRESS=${DBUS_SESSION_BUS_ADDRESS:-unix:path=/run/user/$(id -u)/bus}
Environment=XDG_CURRENT_DESKTOP=${XDG_CURRENT_DESKTOP:-KDE}
ExecStart=$(command -v java) -Duser.home=$PROFILE_DIR -Djava.util.prefs.userRoot=$PREFS_DIR -jar $jar
Restart=on-failure
RestartSec=2
StandardOutput=append:$LOG_FILE
StandardError=append:$LOG_FILE

[Install]
WantedBy=default.target
EOF
}

start_service() {
  build_jar
  local jar
  jar="$(jar_path)"
  if [ ! -f "$jar" ]; then
    echo "Jar not found after build: $jar" >&2
    exit 1
  fi
  write_unit "$jar"
  systemctl --user daemon-reload
  systemctl --user restart "$SERVICE_NAME"
  systemctl --user --no-pager --full status "$SERVICE_NAME"
  echo
  echo "MuonSSH test profile: $PROFILE_DIR"
  echo "MuonSSH log: $LOG_FILE"
}

case "$ACTION" in
  start|restart|run)
    start_service
    ;;
  stop)
    systemctl --user stop "$SERVICE_NAME"
    ;;
  status)
    systemctl --user --no-pager --full status "$SERVICE_NAME"
    ;;
  logs)
    tail -n "${2:-120}" "$LOG_FILE"
    ;;
  *)
    echo "Usage: $0 [start|restart|run|stop|status|logs [lines]]" >&2
    exit 2
    ;;
esac
