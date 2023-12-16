#!/usr/bin/env sh

SELF=$(which "$0" 2> /dev/null)
[ $? -gt 0 -a -f "$0" ] && SELF="./$0"

java=java
if test -n "$JAVA_HOME"; then
    java="$JAVA_HOME/bin/java"
fi

exec "$java" -jar $SELF "$@"
exit 1
