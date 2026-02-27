package com.chatflow.search.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "blog_posts")
public class BlogPostDocument {

    @Id
    private String id;

    @Field(type = FieldType.Text, analyzer = "korean_analyzer", searchAnalyzer = "korean_search_analyzer")
    private String title;

    @Field(type = FieldType.Text, analyzer = "korean_analyzer", searchAnalyzer = "korean_search_analyzer")
    private String content;

    @Field(type = FieldType.Keyword)
    private String author;

    @Field(type = FieldType.Keyword)
    private String link;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Date)
    private LocalDateTime publishedDate;
}
