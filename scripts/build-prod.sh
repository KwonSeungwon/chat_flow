#!/bin/bash

# ChatFlow Production Build Script for K8s Deployment

set -e

# 색상 정의
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${CYAN}🏢 Building ChatFlow for Production (K8s) Deployment${NC}"
echo ""

# 환경변수 설정
export SPRING_PROFILES_ACTIVE=prod

echo -e "${YELLOW}🔧 Building all services...${NC}"
./gradlew clean build -x test

echo -e "${YELLOW}🐳 Building Docker images...${NC}"

# Gateway Service
echo -e "${YELLOW}Building Gateway Service image...${NC}"
./gradlew :gateway-service:bootBuildImage --imageName=chatflow/gateway:latest

# Chat Service  
echo -e "${YELLOW}Building Chat Service image...${NC}"
./gradlew :chat-service:bootBuildImage --imageName=chatflow/chat:latest

# AI Summary Service
echo -e "${YELLOW}Building AI Summary Service image...${NC}"
./gradlew :ai-summary-service:bootBuildImage --imageName=chatflow/ai-summary:latest

# Search Service
echo -e "${YELLOW}Building Search Service image...${NC}"
./gradlew :search-service:bootBuildImage --imageName=chatflow/search:latest

echo ""
echo -e "${GREEN}✅ ChatFlow Production Images Built Successfully!${NC}"
echo ""
echo -e "${CYAN}📦 Built Images:${NC}"
echo -e "  chatflow/gateway:latest"
echo -e "  chatflow/chat:latest"
echo -e "  chatflow/ai-summary:latest"
echo -e "  chatflow/search:latest"
echo ""
echo -e "${CYAN}🚀 Next Steps:${NC}"
echo -e "  1. Push images to your container registry"
echo -e "  2. Deploy to K8s cluster with your manifests"
echo -e "  3. Configure external infrastructure (DB, Valkey, Elasticsearch)"
echo ""