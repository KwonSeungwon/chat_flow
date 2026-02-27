package com.chatflow.search.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.chatflow.common.dto.ApiResponse;
import com.chatflow.search.document.BlogPostDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/search/blog")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class BlogSearchController {

    private final ElasticsearchClient elasticsearchClient;

    private static final String INDEX_NAME = "blog_posts";

    @GetMapping
    public ApiResponse<List<BlogPostDocument>> searchBlogPosts(
            @RequestParam String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        try {
            Query searchQuery = MultiMatchQuery.of(m -> m
                    .query(keyword)
                    .fields("title^3", "title.ngram^1", "content^2", "content.ngram^1")
                    .type(TextQueryType.BestFields)
                    .fuzziness("AUTO")
            )._toQuery();

            Query finalQuery;
            if (category != null && !category.isBlank()) {
                finalQuery = Query.of(q -> q.bool(b -> b
                        .must(searchQuery)
                        .filter(f -> f.term(t -> t.field("category").value(category)))
                ));
            } else {
                finalQuery = searchQuery;
            }

            SearchRequest request = SearchRequest.of(s -> s
                    .index(INDEX_NAME)
                    .query(finalQuery)
                    .from(page * size)
                    .size(Math.min(size, 50))
                    .sort(sort -> sort.score(sc -> sc.order(SortOrder.Desc)))
                    .highlight(h -> h
                            .fields("title", hf -> hf.preTags("<mark>").postTags("</mark>"))
                            .fields("content", hf -> hf.preTags("<mark>").postTags("</mark>"))
                    )
            );

            SearchResponse<BlogPostDocument> response = elasticsearchClient.search(request, BlogPostDocument.class);
            List<BlogPostDocument> results = extractHits(response);

            return ApiResponse.ok(results, results.size() + "건 검색됨");
        } catch (Exception e) {
            log.error("Blog search failed for keyword: {}", keyword, e);
            return ApiResponse.error("블로그 검색에 실패했습니다: " + e.getMessage());
        }
    }

    @GetMapping("/recent")
    public ApiResponse<List<BlogPostDocument>> getRecentPosts(
            @RequestParam(defaultValue = "10") int size) {

        try {
            SearchRequest request = SearchRequest.of(s -> s
                    .index(INDEX_NAME)
                    .query(q -> q.matchAll(m -> m))
                    .from(0)
                    .size(Math.min(size, 50))
                    .sort(sort -> sort.field(f -> f
                            .field("publishedDate")
                            .order(SortOrder.Desc)
                    ))
            );

            SearchResponse<BlogPostDocument> response = elasticsearchClient.search(request, BlogPostDocument.class);
            List<BlogPostDocument> results = extractHits(response);

            return ApiResponse.ok(results);
        } catch (Exception e) {
            log.error("Failed to fetch recent blog posts", e);
            return ApiResponse.error("최근 블로그 글 조회에 실패했습니다");
        }
    }

    private List<BlogPostDocument> extractHits(SearchResponse<BlogPostDocument> response) {
        List<BlogPostDocument> docs = new ArrayList<>();
        for (Hit<BlogPostDocument> hit : response.hits().hits()) {
            if (hit.source() != null) {
                docs.add(hit.source());
            }
        }
        return docs;
    }
}
