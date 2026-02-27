package com.chatflow.search.document;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;

@Data
@Builder
@Document(indexName = "chat_messages")
public class ChatMessageDocument {
    
    @Id
    private String id;
    
    @Field(type = FieldType.Keyword)
    private String messageId;
    
    @Field(type = FieldType.Keyword)
    private String chatRoomId;
    
    @Field(type = FieldType.Keyword)
    private String userId;
    
    @Field(type = FieldType.Text, analyzer = "korean_analyzer", searchAnalyzer = "korean_search_analyzer")
    private String username;
    
    @Field(type = FieldType.Text, analyzer = "korean_analyzer", searchAnalyzer = "korean_search_analyzer")
    private String content;
    
    @Field(type = FieldType.Date)
    private LocalDateTime timestamp;
    
    @Field(type = FieldType.Keyword)
    private String messageType;
    
    @Field(type = FieldType.Boolean)
    private boolean isAiGenerated;
}