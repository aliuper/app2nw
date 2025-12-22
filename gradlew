#!/bin/sh

APP_HOME=$(cd "${APP_HOME:-$(dirname "$0")}" && pwd -P) || exit

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
MAX_FD=maximum

warn () {
    echo "$*"
}

die () {
    echo
    echo "$*"
    echo
    exit 1
}

cygwin=false
msys=false
darwin=false
nonstop=false
case "$(uname)" in
  CYGWIN* ) cygwin=true ;;
  Darwin* ) darwin=true ;;
  MSYS* | MINGW* ) msys=true ;;
  NONSTOP* ) nonstop=true ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME\n\nPlease set the JAVA_HOME variable in your environment to match the\nlocation of your Java installation."
    fi
else
    JAVACMD=java
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.\n\nPlease set the JAVA_HOME variable in your environment to match the\nlocation of your Java installation."
fi

if ! "$cygwin" && ! "$darwin" && ! "$nonstop" ; then
    MAX_FD_LIMIT=$(ulimit -H -n) || warn "Could not query maximum file descriptor limit"
    if [ "$MAX_FD" = "maximum" ] ; then
        MAX_FD="$MAX_FD_LIMIT"
    fi
    ulimit -n "$MAX_FD" || warn "Could not set maximum file descriptor limit to $MAX_FD"
fi

set -- \
        "-Dorg.gradle.appname=$APP_BASE_NAME" \
        -classpath "$CLASSPATH" \
        org.gradle.wrapper.GradleWrapperMain \
        "$@"

DEFAULT_JVM_OPTS=${DEFAULT_JVM_OPTS:-""}

exec "$JAVACMD" $DEFAULT_JVM_OPTS "$@"
