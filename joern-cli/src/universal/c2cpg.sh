#!/usr/bin/env sh

if [ "$(uname -s)" = "Darwin" ]; then
    SCRIPT_ABS_PATH=$(greadlink -f "$0")
else
    SCRIPT_ABS_PATH=$(readlink -f "$0")
fi

SCRIPT_ABS_DIR=$(dirname "$SCRIPT_ABS_PATH")
SCRIPT="$SCRIPT_ABS_DIR"/frontends/c2cpg/bin/c2cpg
# Uncomment if running standalone
# Running from root, ABS_DIR is joern-cli/src/universal
# We want joern-cli/frontends/c2cpg/target/universal/scripts/bin
#SCRIPT_ABS_DIR==$(dirname "$SCRIPT_ABS_PATH")/../../target/universal/stage
#SCRIPT=joern-cli/frontends/c2cpg/target/universal/scripts/bin/c2cpg

if [ ! -f "$SCRIPT" ]; then
    echo "Unable to find $SCRIPT, have you created the distribution?"
    exit 1
fi

$SCRIPT -J-XX:+UseG1GC -J-XX:CompressedClassSpaceSize=128m -Dlog4j.configurationFile="$SCRIPT_ABS_DIR"/conf/log4j2.xml "$@"
