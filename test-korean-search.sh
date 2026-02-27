#!/bin/bash

echo "=== Korean Search Test Script ==="
echo

# Elasticsearch 연결 확인
echo "1. Checking Elasticsearch connection..."
curl -s "http://localhost:9200/_cluster/health" | jq .
echo

# 인덱스 설정 확인
echo "2. Checking chat_messages index settings..."
curl -s "http://localhost:9200/chat_messages/_settings" | jq .
echo

# 매핑 정보 확인
echo "3. Checking chat_messages index mappings..."
curl -s "http://localhost:9200/chat_messages/_mapping" | jq .
echo

# 테스트 문서 인덱싱
echo "4. Indexing test Korean documents..."
curl -X POST "http://localhost:9200/chat_messages/_doc/test1" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId": "test1",
    "chatRoomId": "general",
    "userId": "user1",
    "username": "김철수",
    "content": "안녕하세요! 오늘 날씨가 정말 좋네요. 산책하기 딱 좋은 날입니다.",
    "timestamp": "2023-12-01T10:00:00",
    "messageType": "TEXT",
    "isAiGenerated": false
  }'
echo

curl -X POST "http://localhost:9200/chat_messages/_doc/test2" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId": "test2",
    "chatRoomId": "general",
    "userId": "user2",
    "username": "박영희",
    "content": "네, 맞아요! 저도 점심시간에 잠깐 밖에 나가서 공원을 걸었어요. 기분이 정말 좋더라고요.",
    "timestamp": "2023-12-01T10:05:00",
    "messageType": "TEXT",
    "isAiGenerated": false
  }'
echo

curl -X POST "http://localhost:9200/chat_messages/_doc/test3" \
  -H "Content-Type: application/json" \
  -d '{
    "messageId": "test3",
    "chatRoomId": "tech",
    "userId": "user3",
    "username": "개발자",
    "content": "Spring Boot와 Elasticsearch 연동이 생각보다 복잡하네요. Nori 분석기는 어떻게 설정하나요?",
    "timestamp": "2023-12-01T14:00:00",
    "messageType": "TEXT",
    "isAiGenerated": false
  }'
echo

# 인덱스 새로고침
echo "5. Refreshing index..."
curl -X POST "http://localhost:9200/chat_messages/_refresh"
echo

# 테스트 검색 쿼리들
echo "6. Testing Korean search queries..."

echo "- Searching for '날씨':"
curl -s -X GET "http://localhost:9200/chat_messages/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": {
      "multi_match": {
        "query": "날씨",
        "fields": ["content^2", "content.ngram^1"]
      }
    },
    "highlight": {
      "fields": {
        "content": {}
      }
    }
  }' | jq '.hits.hits[] | {_source, highlight}'
echo

echo "- Searching for '공원' (n-gram):"
curl -s -X GET "http://localhost:9200/chat_messages/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": {
      "multi_match": {
        "query": "공원",
        "fields": ["content.ngram^2"]
      }
    }
  }' | jq '.hits.hits[] | ._source.content'
echo

echo "- Searching for 'Spring' (mixed language):"
curl -s -X GET "http://localhost:9200/chat_messages/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": {
      "multi_match": {
        "query": "Spring",
        "fields": ["content^2", "content.ngram^1"]
      }
    }
  }' | jq '.hits.hits[] | ._source.content'
echo

echo "=== Test completed ==="