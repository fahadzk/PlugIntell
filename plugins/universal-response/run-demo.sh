#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp 'plugins/universal-response/target/universal-response-plugin.jar:pipeline-api/target/pipeline-api-1.0.0.jar' com.core.docintel.response.UniversalResponseDemo "$@"
