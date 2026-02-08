#!/bin/sh
set -e
git add .github/*.md
git commit -m "instructions: update .github/*.md";
git pull
git push
