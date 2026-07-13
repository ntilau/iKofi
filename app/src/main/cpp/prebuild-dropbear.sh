#!/bin/bash

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
SRC_DIR="$SCRIPT_DIR/dropbear/src"

if [ ! -d "$SRC_DIR" ]; then
    echo "Error: '$SRC_DIR' directory not found."
    exit 1
fi

# Open a subshell context so we can run this script from anywhere
(
    cd "$SRC_DIR"

    # -nt Compares modification time of two files and determines which one is newer.
    if [ default_options.h -nt default_options_guard.h ]; then
        ./ifndef_wrapper.sh < default_options.h > default_options_guard.h
    fi
)
