#!/bin/sh

set -xe

git pull
git add .github/*.md
git commit -m "instructions: update .github/*.md";
git push
