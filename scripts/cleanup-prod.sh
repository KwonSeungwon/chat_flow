#!/bin/bash

# ChatFlow Production Image Cleanup Script

set -e

# 색상 정의
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${CYAN}🧹 ChatFlow Production Image Cleanup${NC}"
echo ""

echo -e "${YELLOW}📦 Current ChatFlow images:${NC}"
docker images | grep chatflow

echo ""
read -p "$(echo -e ${YELLOW}Do you want to remove all ChatFlow images? [y/N]: ${NC})" -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo -e "${YELLOW}Removing ChatFlow images...${NC}"
    docker images | grep chatflow | awk '{print $3}' | xargs -r docker rmi -f
    echo -e "${GREEN}✅ ChatFlow images removed${NC}"
else
    echo -e "${GREEN}Operation cancelled.${NC}"
fi

echo ""
echo -e "${YELLOW}🧹 Clean up unused Docker resources? [y/N]: ${NC}"
read -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    docker system prune -f
    echo -e "${GREEN}✅ Docker cleanup completed${NC}"
fi

echo ""