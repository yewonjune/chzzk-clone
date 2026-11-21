#!/usr/bin/env bash
# 치지직 6단계: OCI VM(Ubuntu 22.04/24.04, ARM64 또는 x86_64) 최초 준비
#   1) Docker Engine + Compose 플러그인 설치
#   2) OS 방화벽(iptables)에 80·443(+선택 TURN 포트) 허용 — OCI Ubuntu 이미지는 22번 외에는 기본으로 막혀 있음
#   3) 저장소 받기
# 사용: ssh ubuntu@<VM 공인 IP>  후
#   curl -fsSL https://raw.githubusercontent.com/yewonjune/chzzk-clone/main/deploy/oci/setup-vm.sh -o setup-vm.sh
#   less setup-vm.sh   # 내용 확인
#   bash setup-vm.sh            # TURN 포트까지 열려면: bash setup-vm.sh --turn
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/yewonjune/chzzk-clone.git}"
APP_DIR="${APP_DIR:-$HOME/chzzk-clone}"
OPEN_TURN=false
[[ "${1:-}" == "--turn" ]] && OPEN_TURN=true

echo "== 1) Docker 설치 =="
if ! command -v docker >/dev/null 2>&1; then
  sudo apt-get update -y
  sudo apt-get install -y ca-certificates curl git
  sudo install -m 0755 -d /etc/apt/keyrings
  sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  sudo chmod a+r /etc/apt/keyrings/docker.asc
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
  sudo apt-get update -y
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  sudo usermod -aG docker "$USER"
  echo "   (docker 그룹 반영을 위해 이 스크립트가 끝나면 한 번 로그아웃 후 다시 접속하세요)"
else
  echo "   이미 설치됨: $(docker --version)"
fi

echo "== 2) OS 방화벽: 80, 443 허용 =="
open_port() { # proto port
  if ! sudo iptables -C INPUT -p "$1" --dport "$2" -m state --state NEW -j ACCEPT 2>/dev/null; then
    # OCI 이미지의 마지막 REJECT 규칙보다 앞(6번째)에 넣음
    sudo iptables -I INPUT 6 -p "$1" --dport "$2" -m state --state NEW -j ACCEPT
    echo "   열림: $1/$2"
  fi
}
open_port tcp 80
open_port tcp 443
open_port udp 443
if $OPEN_TURN; then
  open_port tcp 3478
  open_port udp 3478
  sudo iptables -C INPUT -p udp --dport 49160:49200 -j ACCEPT 2>/dev/null \
    || sudo iptables -I INPUT 6 -p udp --dport 49160:49200 -j ACCEPT
  echo "   열림: TURN 3478, 49160-49200/udp"
fi
if command -v netfilter-persistent >/dev/null 2>&1; then
  sudo netfilter-persistent save
else
  sudo apt-get install -y iptables-persistent && sudo netfilter-persistent save
fi
echo "   ※ OCI 콘솔의 보안 목록(Security List)에도 같은 포트의 수신 규칙이 있어야 합니다 (README 참고)"

echo "== 3) 저장소 =="
if [[ -d "$APP_DIR/.git" ]]; then
  git -C "$APP_DIR" pull --ff-only
else
  git clone "$REPO_URL" "$APP_DIR"
fi
if [[ ! -f "$APP_DIR/deploy/oci/.env" ]]; then
  cp "$APP_DIR/deploy/oci/.env.example" "$APP_DIR/deploy/oci/.env"
  chmod 600 "$APP_DIR/deploy/oci/.env"
  echo "   deploy/oci/.env 를 만들었습니다. DOMAIN·ACME_EMAIL·DB_PASSWORD 를 채우세요: nano $APP_DIR/deploy/oci/.env"
fi

cat <<EOF

다음 단계:
  cd $APP_DIR
  docker compose -f deploy/oci/compose.yaml --env-file deploy/oci/.env up -d --build
  docker compose -f deploy/oci/compose.yaml --env-file deploy/oci/.env ps
EOF
