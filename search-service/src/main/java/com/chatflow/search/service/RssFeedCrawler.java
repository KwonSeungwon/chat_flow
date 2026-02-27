package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.chatflow.search.document.BlogPostDocument;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RssFeedCrawler {

    private final ElasticsearchClient elasticsearchClient;

    @Value("${rss.feed-url:}")
    private String feedUrl;

    private static final String INDEX_NAME = "blog_posts";

    @Scheduled(fixedDelayString = "${rss.crawl-interval:3600000}")
    public void crawlRssFeed() {
        if (feedUrl == null || feedUrl.isBlank()) {
            log.debug("RSS feed URL not configured, skipping crawl");
            return;
        }

        log.info("Starting RSS feed crawl: {}", feedUrl);
        int indexed = 0;

        try {
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(URI.create(feedUrl).toURL()));

            for (SyndEntry entry : feed.getEntries()) {
                try {
                    if (isAlreadyIndexed(entry.getLink())) {
                        continue;
                    }
                    indexBlogPost(entry);
                    indexed++;
                } catch (Exception e) {
                    log.warn("Failed to index blog post: {}", entry.getTitle(), e);
                }
            }

            log.info("RSS crawl completed. New posts indexed: {}", indexed);
        } catch (Exception e) {
            log.error("Failed to crawl RSS feed: {}", feedUrl, e);
        }
    }

    private boolean isAlreadyIndexed(String link) {
        try {
            String docId = generateDocId(link);
            GetResponse<BlogPostDocument> response = elasticsearchClient.get(
                    GetRequest.of(g -> g.index(INDEX_NAME).id(docId)),
                    BlogPostDocument.class
            );
            return response.found();
        } catch (Exception e) {
            return false;
        }
    }

    private void indexBlogPost(SyndEntry entry) throws Exception {
        String content = stripHtml(
                entry.getDescription() != null ? entry.getDescription().getValue() : ""
        );

        String category = entry.getCategories().isEmpty()
                ? "Uncategorized"
                : entry.getCategories().get(0).getName();

        BlogPostDocument doc = BlogPostDocument.builder()
                .id(generateDocId(entry.getLink()))
                .title(entry.getTitle())
                .content(content)
                .author(entry.getAuthor())
                .link(entry.getLink())
                .category(category)
                .publishedDate(toLocalDateTime(entry.getPublishedDate()))
                .build();

        elasticsearchClient.index(IndexRequest.of(i -> i
                .index(INDEX_NAME)
                .id(doc.getId())
                .document(doc)
        ));

        log.debug("Indexed blog post: {}", entry.getTitle());
    }

    private String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        String text = html
                .replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
                .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > 10000 ? text.substring(0, 10000) : text;
    }

    private String generateDocId(String link) {
        return UUID.nameUUIDFromBytes(link.getBytes()).toString();
    }

    private LocalDateTime toLocalDateTime(Date date) {
        if (date == null) return LocalDateTime.now();
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
