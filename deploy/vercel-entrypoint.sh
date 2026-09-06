#!/bin/sh
set -eu
case "${PORT:-8080}" in
  ''|*[!0-9]*) echo 'PORT must be numeric' >&2; exit 1 ;;
esac
mkdir -p "$CATALINA_BASE/conf" "$CATALINA_BASE/logs" "$CATALINA_BASE/temp" "$CATALINA_BASE/work" "$CATALINA_BASE/webapps"
cp "$CATALINA_HOME/conf/"* "$CATALINA_BASE/conf/"
cp /opt/roam/server.xml "$CATALINA_BASE/conf/server.xml"
cp /opt/roam/ROOT.war "$CATALINA_BASE/webapps/ROOT.war"
export CATALINA_OPTS="${CATALINA_OPTS:-} -Droam.http.port=${PORT:-8080}"
exec "$CATALINA_HOME/bin/catalina.sh" run
