#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Orbit Platform — Installer  (macOS · Linux · Windows WSL / Git Bash)
# Run:  bash install.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'
BOLD='\033[1m'; NC='\033[0m'
ok()   { echo -e "${GREEN}✓${NC} $*"; }
info() { echo -e "${BLUE}→${NC} $*"; }
warn() { echo -e "${YELLOW}⚠${NC} $*"; }
err()  { echo -e "${RED}✗${NC} $*" >&2; }

echo -e "\n${BOLD}${BLUE}Orbit Platform Installer${NC}\n"

# ── Check prerequisites ────────────────────────────────────────────────────
MISSING=()

# Java 21+
if command -v java &>/dev/null; then
  RAW=$(java -version 2>&1 | head -1)
  VER=$(echo "$RAW" | sed -E 's/.*version "([0-9]+).*/\1/')
  if [ "${VER:-0}" -ge 21 ] 2>/dev/null; then
    ok "Java $VER"
  else
    err "Java 21+ required (found: $RAW)"
    err "  macOS:  brew install --cask temurin"
    err "  Ubuntu: sudo apt install temurin-21-jdk"
    err "  Other:  https://adoptium.net"
    MISSING+=("java")
  fi
else
  err "Java not found — install Java 21: https://adoptium.net"
  MISSING+=("java")
fi

# Maven
if command -v mvn &>/dev/null; then
  ok "Maven $(mvn -v 2>/dev/null | head -1 | awk '{print $3}')"
else
  err "Maven not found"
  err "  macOS:  brew install maven"
  err "  Ubuntu: sudo apt install maven"
  err "  Other:  https://maven.apache.org/install.html"
  MISSING+=("mvn")
fi

# Docker
if command -v docker &>/dev/null && docker info &>/dev/null 2>&1; then
  ok "Docker $(docker --version | awk '{print $3}' | tr -d ',')"
else
  warn "Docker not running — start Docker Desktop or Docker daemon first"
  warn "  macOS/Windows: https://docs.docker.com/desktop"
  warn "  Linux:         https://docs.docker.com/engine/install"
  MISSING+=("docker")
fi

# curl
if command -v curl &>/dev/null; then ok "curl"
else err "curl not found — install via package manager"; MISSING+=("curl"); fi

if [ ${#MISSING[@]} -gt 0 ]; then
  echo ""
  err "Missing prerequisites: ${MISSING[*]}"
  err "Fix the above then re-run:  bash install.sh"
  exit 1
fi

# ── Build ──────────────────────────────────────────────────────────────────
echo ""
info "Building Orbit Platform..."
cd "$SCRIPT_DIR"
mvn clean package -DskipTests --no-transfer-progress -q
ok "Build complete"

# ── Install CLI ────────────────────────────────────────────────────────────
echo ""
info "Installing orbit CLI..."

ORBIT_BIN="$SCRIPT_DIR/bin/orbit"
chmod +x "$ORBIT_BIN"
chmod +x "$SCRIPT_DIR/orbit"

# Pick install dir (prefer /usr/local/bin, fall back to ~/bin)
if [ -w /usr/local/bin ]; then
  ln -sf "$ORBIT_BIN" /usr/local/bin/orbit
  INSTALL_PATH="/usr/local/bin/orbit"
elif sudo -n true 2>/dev/null; then
  sudo ln -sf "$ORBIT_BIN" /usr/local/bin/orbit
  INSTALL_PATH="/usr/local/bin/orbit"
else
  mkdir -p "$HOME/bin"
  ln -sf "$ORBIT_BIN" "$HOME/bin/orbit"
  INSTALL_PATH="$HOME/bin/orbit"
  warn "Installed to ~/bin/orbit"
  warn "Add to PATH if not already:"
  warn "  echo 'export PATH=\"\$HOME/bin:\$PATH\"' >> ~/.bashrc && source ~/.bashrc"
fi

ok "CLI installed → $INSTALL_PATH"

# ── Verify ─────────────────────────────────────────────────────────────────
echo ""
if command -v orbit &>/dev/null || [ -x "$INSTALL_PATH" ]; then
  ok "orbit command is available"
else
  warn "Run manually:  $ORBIT_BIN <command>"
fi

# ── Print usage ────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${GREEN}Installation complete!${NC}"
echo ""
echo -e "${BOLD}Get started in 3 commands:${NC}"
echo ""
echo "  orbit up        # start Postgres, Redis, Kafka, Grafana"
echo "  orbit start     # start the Spring Boot app"
echo "  orbit smoke     # verify everything works end-to-end"
echo ""
echo -e "${BOLD}Then explore:${NC}"
echo ""
echo "  orbit jobs                                              # list all jobs"
echo "  orbit jobs create daily-report '0 0 8 * * *' https://api/run"
echo "  orbit exec 1 SUCCESS                                   # record execution"
echo "  orbit dashboard                                         # live stats"
echo "  orbit load                                              # k6 load test"
echo "  orbit help                                              # all commands"
echo ""
echo "  Dashboard:   http://localhost:8080"
echo "  Grafana:     http://localhost:3000  (admin / admin)"
echo "  Kafka UI:    http://localhost:8090"
echo ""
