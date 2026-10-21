#!/bin/bash
set -e
gcc -O2 -Wall -Wextra dsh_tool.c -o dsh-notify
echo "Compiled dsh-notify successfully!"
