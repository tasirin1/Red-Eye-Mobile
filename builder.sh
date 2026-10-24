#!/bin/bash

# LEGACY — skrip ini DIHENTIKAN (sejak v1.6.379). Alur lama menulis token bot ke
# app/build.gradle yang di-track Git, dan itu dilarang keras oleh aturan repo.
# Build hanya lewat GitHub Actions (.github/workflows/build.yml).
echo "LEGACY: builder.sh dihentikan. Token bot tak boleh ditulis ke app/build.gradle." >&2
echo "Build hanya via GitHub Actions (lihat AGENTS.md dan README.md)." >&2
exit 1
