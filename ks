#!/bin/sh
# The kitchensink tool: ks start, ks rehearse, ks cutover, ks rollback... (ks help)
exec java -jar "$(dirname "$0")/local/target/kitchensink-local-exec.jar" "$@"
