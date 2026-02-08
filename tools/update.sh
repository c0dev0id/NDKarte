#!/bin/sh -e

if [ -z "$1" ]
then
    echo "invalid argument"
    exit 2
fi
git fetch --all
git pull --rebase origin $1
