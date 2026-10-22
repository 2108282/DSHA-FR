#!/bin/bash
set -e
gcc -static -O2 -Wall -Wextra dsh_tool.c -o dsh-notify
echo "Compiled static dsh-notify successfully!"
