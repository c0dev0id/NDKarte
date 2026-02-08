#!/bin/sh
set -e
git pull
git add .github/*.md
git commit -m "instructions: update .github/*.md";
git push
