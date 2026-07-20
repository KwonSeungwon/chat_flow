# Legacy Spring-Data-Elasticsearch Stack Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove search-service's legacy Spring-Data-Elasticsearch stack (repository + starter + `@Document`/`@Field` annotations) and consolidate all read + write paths onto the low-level `co.elastic.clients.elasticsearch.ElasticsearchClient` that `KoreanSearchService` already uses.

**Architecture:** Three moving parts. (1) SearchController's 4 legacy read endpoints get rewired to delegate to `KoreanSearchService` (analyzer-based multi-match/filters) and `SearchService`'s 6 read methods are deleted. (2) `SearchService`'s Kafka **indexing write path** (`saveAll`/`deleteById` on the Spring-Data repository) is rewritten to the low-level client's `bulk`/`delete` APIs, keeping the existing buffer/batch/retry logic. (3) The now-unused Spring-Data artifacts are deleted: `ChatMessageSearchRepository`, `@EnableElasticsearchRepositories`, the spring-data annotations on the two document classes, and the `spring-boot-starter-data-elasticsearch` dependency (replaced with the explicit low-level client libs it used to pull transitively). A Testcontainers ES integration test protects the rewritten write path.

**Tech Stack:** Spring Boot 3.2 / Java 17 / `co.elastic.clients:elasticsearch-java` (low-level client) / Elasticsearch 8.11 / Testcontainers-Elasticsearch / JUnit5 + Mockito + MockMvc.

**Constraints / decisions (READ FIRST):**
- **Intentional behavior change (called out, accepted):** The 4 legacy read endpoints move from Spring-Data `*Containing` derived queries (naive substring `wildcard`-style match) to `KoreanSearchService`'s analyzer-based multi-match + `term`/`range` filters. This is the *point* of the consolidation (better Korean search). Notably, username filtering becomes a `term` filter on the analyzed `username` field — this is **pre-existing `KoreanSearchService` semantics** (already used by `/filter`); do NOT try to "fix" it here, just inherit it. Tests assert the **new delegation**, not the old substring semantics.
- **Write-path parity is non-negotiable:** the bulk index op MUST set `.id(doc.getId())` so edits upsert the same ES `_id` (matching Spring-Data's `@Id`-driven `saveAll` behavior). Deletes MUST target the same id.
- **The low-level client libs are currently transitive via the starter.** Removing the starter removes them, so they must be re-declared explicitly, PINNED to the exact versions currently resolved (deterministic — no behavior drift). Task 4 gives the exact procedure.
- **Document annotations must be stripped, not kept:** once `spring-boot-starter-data-elasticsearch` is gone, `org.springframework.data.annotation.Id` / `org.springframework.data.elasticsearch.annotations.*` won't be on the classpath, so `ChatMessageDocument`/`BlogPostDocument` won't compile with them. They are already inert for the low-level (Jackson) path, so removal is behavior-neutral. Index mappings come from `IndexInitializer`'s JSON files, not annotations.
- **Do NOT touch** `KoreanSearchService`, `BlogSearchController`, `AuditIndexService`, `RssFeedCrawler`, `IndexInitializer`, `ElasticsearchConfig` (they are already 100% low-level client) except where a task explicitly says so.
- Keep the class named `SearchService` (renaming is out of scope — minimize blast radius); update its javadoc to reflect its now indexing-only responsibility.

---

## Current-state reference (verified)

- `SearchController` (`search-service/.../controller/SearchController.java`) injects `SearchService` + `KoreanSearchService`. 7 endpoints: 4 legacy (`/messages`, `/rooms/{roomId}/messages`, `/rooms/{roomId}/users`, `/rooms/{roomId}/time-range` → SearchService) + 3 new (`/korean`, `/ngram`, `/rooms/{roomId}/filter` → KoreanSearchService).
- `SearchService` (`.../service/SearchService.java`): constructor `(ChatMessageSearchRepository, ObjectMapper, MeterRegistry)`. Kafka listener `indexChatMessage` buffers docs, `flushBuffer()` calls `searchRepository.saveAll(batch)`, deletes call `searchRepository.deleteById(id)`. Also 6 read methods delegating to the repo.
- `ChatMessageSearchRepository` (`.../repository/`): `extends ElasticsearchRepository<ChatMessageDocument, String>`, 9 derived finders. Injected ONLY by SearchService.
- `KoreanSearchService`: `searchKoreanContent(query, chatRoomId, pageable)`, `searchWithNgram(...)`, `searchWithFilters(roomId, query, username, startDate, endDate, messageType, pageable)`. All via `ElasticsearchClient`.
- `SearchConstants.CHAT_MESSAGES_INDEX` = the chat messages index name.
- `SearchServiceApplication`: has `@EnableElasticsearchRepositories`.
- `ChatMessageDocument` / `BlogPostDocument`: carry `@Document`/`@Field`/`@Id` (spring-data-es) + Lombok `@Data @Builder`. `@JsonIgnoreProperties`/Lombok stay.
- `build.gradle`: `implementation 'org.springframework.boot:spring-boot-starter-data-elasticsearch'` (line 5); `testImplementation 'org.testcontainers:elasticsearch'` already present.
- Tests: `SearchServiceDeleteTest` (mocks repo, verifies deleteById/saveAll), `FilterEndpointTest` (@SpringBootTest+MockMvc; mocks KoreanSearchService, SearchService, ChatMessageSearchRepository, ElasticsearchOperations, KafkaTemplate), `KoreanSearchServiceTest` (34 char. tests, untouched), `SearchServiceApplicationTest` (context smoke).

---

## Task 1: Rewire the 4 legacy read endpoints onto KoreanSearchService; delete SearchService's read methods

**Files:**
- Modify: `search-service/src/main/java/com/chatflow/search/controller/SearchController.java`
- Modify: `search-service/src/main/java/com/chatflow/search/service/SearchService.java` (delete 6 read methods only)
- Test: `search-service/src/test/java/com/chatflow/search/controller/SearchControllerLegacyDelegationTest.java` (new)

- [ ] **Step 1: Write failing controller delegation tests.** Create `SearchControllerLegacyDelegationTest.java` mirroring `FilterEndpointTest`'s harness (same `@SpringBootTest(properties=…exclude DataSource/JPA…)` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `TestConfig` MeterRegistry bean + `@MockBean KafkaTemplate`). Mock ONLY `KoreanSearchService` (do NOT mock SearchService/repository — the goal is that the controller no longer needs them for reads). Assert each rewired endpoint delegates correctly:

```java
package com.chatflow.search.controller;

import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.service.KoreanSearchService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchControllerLegacyDelegationTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private KoreanSearchService koreanSearchService;
    @MockBean @SuppressWarnings("rawtypes") private KafkaTemplate kafkaTemplate;

    @TestConfiguration
    static class TestConfig {
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }

    private static Page<ChatMessageDocument> emptyPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
    }

    @Test
    void messages_delegatesToKoreanContentWithNullRoom() throws Exception {
        when(koreanSearchService.searchKoreanContent(eq("hi"), isNull(), any(Pageable.class))).thenReturn(emptyPage());
        mockMvc.perform(get("/api/search/messages").param("query", "hi")).andExpect(status().isOk());
        verify(koreanSearchService).searchKoreanContent(eq("hi"), isNull(), any(Pageable.class));
    }

    @Test
    void roomMessages_delegatesToKoreanContentWithRoom() throws Exception {
        when(koreanSearchService.searchKoreanContent(eq("hi"), eq("room1"), any(Pageable.class))).thenReturn(emptyPage());
        mockMvc.perform(get("/api/search/rooms/room1/messages").param("query", "hi")).andExpect(status().isOk());
        verify(koreanSearchService).searchKoreanContent(eq("hi"), eq("room1"), any(Pageable.class));
    }

    @Test
    void users_withoutQuery_delegatesToFiltersWithUsernameOnly() throws Exception {
        when(koreanSearchService.searchWithFilters(eq("room1"), isNull(), eq("alice"), isNull(), isNull(), isNull(), any(Pageable.class))).thenReturn(emptyPage());
        mockMvc.perform(get("/api/search/rooms/room1/users").param("username", "alice")).andExpect(status().isOk());
        verify(koreanSearchService).searchWithFilters(eq("room1"), isNull(), eq("alice"), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void users_withQuery_delegatesToFiltersWithUsernameAndQuery() throws Exception {
        when(koreanSearchService.searchWithFilters(eq("room1"), eq("hi"), eq("alice"), isNull(), isNull(), isNull(), any(Pageable.class))).thenReturn(emptyPage());
        mockMvc.perform(get("/api/search/rooms/room1/users").param("username", "alice").param("query", "hi")).andExpect(status().isOk());
        verify(koreanSearchService).searchWithFilters(eq("room1"), eq("hi"), eq("alice"), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void users_missingUsername_returns400() throws Exception {
        mockMvc.perform(get("/api/search/rooms/room1/users")).andExpect(status().isBadRequest());
    }

    @Test
    void timeRange_delegatesToFiltersWithDates() throws Exception {
        when(koreanSearchService.searchWithFilters(eq("room1"), isNull(), isNull(), any(LocalDateTime.class), any(LocalDateTime.class), isNull(), any(Pageable.class))).thenReturn(emptyPage());
        mockMvc.perform(get("/api/search/rooms/room1/time-range")
                .param("start", "2026-01-01T00:00:00").param("end", "2026-02-01T00:00:00"))
                .andExpect(status().isOk());
        verify(koreanSearchService).searchWithFilters(eq("room1"), isNull(), isNull(), any(LocalDateTime.class), any(LocalDateTime.class), isNull(), any(Pageable.class));
    }

    @Test
    void timeRange_startAfterEnd_returns400() throws Exception {
        mockMvc.perform(get("/api/search/rooms/room1/time-range")
                .param("start", "2026-02-01T00:00:00").param("end", "2026-01-01T00:00:00"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run it, confirm it fails to compile / fails** (SearchController still calls SearchService read methods; the delegation isn't there yet). Run: `./gradlew :search-service:test --tests 'com.chatflow.search.controller.SearchControllerLegacyDelegationTest'` → FAIL/compile error.

- [ ] **Step 3: Rewire SearchController.** Remove the `SearchService searchService` field + import; keep only `KoreanSearchService`. Rewrite the 4 legacy endpoint bodies (keep all validation + param signatures + `MAX_PAGE_SIZE` clamping identical; only change the service call). Preserve the `Sort.by("timestamp").descending()` intent — note KoreanSearchService already sorts by timestamp desc internally, so pass a plain `PageRequest.of(page, clampedSize)` like the other new endpoints do:

```java
// field block:
private final KoreanSearchService koreanSearchService;
private static final int MAX_PAGE_SIZE = 100;

// /messages
validateSearchParams(query, page, size);
Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
Page<ChatMessageDocument> results = koreanSearchService.searchKoreanContent(query, null, pageable);
return ResponseEntity.ok(results);

// /rooms/{roomId}/messages
validateSearchParams(query, page, size);
Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
Page<ChatMessageDocument> results = koreanSearchService.searchKoreanContent(query, roomId, pageable);
return ResponseEntity.ok(results);

// /rooms/{roomId}/users   (keep the username-required guard)
if (username == null || username.isBlank()) {
    throw new IllegalArgumentException("username은 필수입니다.");
}
Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
String q = (query != null && !query.isBlank()) ? query : null;
Page<ChatMessageDocument> results = koreanSearchService.searchWithFilters(roomId, q, username, null, null, null, pageable);
return ResponseEntity.ok(results);

// /rooms/{roomId}/time-range   (keep the start.isAfter(end) guard)
if (start.isAfter(end)) {
    throw new IllegalArgumentException("시작 시간은 종료 시간보다 이전이어야 합니다.");
}
Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
String q = (query != null && !query.isBlank()) ? query : null;
String u = (username != null && !username.isBlank()) ? username : null;
Page<ChatMessageDocument> results = koreanSearchService.searchWithFilters(roomId, q, u, start, end, null, pageable);
return ResponseEntity.ok(results);
```

- [ ] **Step 4: Delete SearchService's 6 read methods** (`searchByContent`, `searchInChatRoom`, `searchByUsername`, `searchByTimeRange`, `searchByUsernameAndContent`, `searchByTimeRangeCombined`) and the now-unused imports (`Page`, `PageRequest`, `Pageable`, `Sort`, `java.time.LocalDateTime` if unused after). SearchService keeps only the Kafka indexing path (still uses the repo for now — removed in Task 3).

- [ ] **Step 5:** Run `./gradlew :search-service:test --tests 'com.chatflow.search.controller.SearchControllerLegacyDelegationTest'` → PASS. Also run the full `:search-service:test` to catch fallout in `FilterEndpointTest` — it will still compile (it mocks SearchService/repo which still exist), but SearchController no longer autowires SearchService; `@MockBean SearchService` remains harmless. Confirm green.

- [ ] **Step 6: Commit** `refactor(search): rewire legacy read endpoints onto KoreanSearchService; drop SearchService read methods`.

## Task 2: Rewrite the indexing write path (saveAll/deleteById → low-level bulk/delete)

**Files:**
- Modify: `search-service/src/main/java/com/chatflow/search/service/SearchService.java`
- Modify: `search-service/src/test/java/com/chatflow/search/service/SearchServiceDeleteTest.java`

- [ ] **Step 1: Rewrite `SearchServiceDeleteTest` to mock `ElasticsearchClient`.** Replace the `ChatMessageSearchRepository` mock with `co.elastic.clients.elasticsearch.ElasticsearchClient`. The delete path is directly assertable; the buffer/bulk path needs `scheduledFlush()` to be driven. New test body:

```java
package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchService — low-level client indexing (bulk) + delete")
class SearchServiceDeleteTest {

    @Mock private ElasticsearchClient elasticsearchClient;
    private SearchService searchService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        searchService = new SearchService(elasticsearchClient, objectMapper, new SimpleMeterRegistry());
    }

    private static String msg(String id, boolean deleted, String type) {
        return """
            {"messageId":"%s","chatRoomId":"room-1","userId":"u1","username":"alice",
             "content":"c","type":"%s","timestamp":"2026-07-04T12:00:00",
             "isDeleted":%s,"isAiGenerated":false}""".formatted(id, type, deleted);
    }

    @Test
    @DisplayName("deleted payload calls client.delete and never buffers/bulk-indexes")
    void deleted_callsDelete() throws Exception {
        searchService.indexChatMessage(msg("del-1", true, "CHAT"));
        verify(elasticsearchClient).delete(any(DeleteRequest.class));
        searchService.scheduledFlush();
        verify(elasticsearchClient, never()).bulk(any(BulkRequest.class));
    }

    @Test
    @DisplayName("normal payload is buffered then bulk-indexed on flush")
    void normal_bulkIndexedOnFlush() throws Exception {
        BulkResponse ok = mock(BulkResponse.class);
        when(ok.errors()).thenReturn(false);
        when(elasticsearchClient.bulk(any(BulkRequest.class))).thenReturn(ok);

        searchService.indexChatMessage(msg("norm-1", false, "CHAT"));
        verify(elasticsearchClient, never()).delete(any(DeleteRequest.class));
        searchService.scheduledFlush();
        verify(elasticsearchClient).bulk(any(BulkRequest.class));
    }

    @Test
    @DisplayName("JOIN/LEAVE/SYSTEM messages are skipped (not buffered)")
    void systemMessage_skipped() throws Exception {
        searchService.indexChatMessage(msg("join-1", false, "JOIN"));
        searchService.scheduledFlush();
        verify(elasticsearchClient, never()).bulk(any(BulkRequest.class));
    }

    @Test
    @DisplayName("deleted JOIN message still deletes")
    void deletedJoin_stillDeletes() throws Exception {
        searchService.indexChatMessage(msg("join-del-1", true, "JOIN"));
        verify(elasticsearchClient).delete(any(DeleteRequest.class));
    }

    @Test
    @DisplayName("missing isDeleted defaults to false → buffered, not deleted")
    void missingDeleted_defaultsFalse() throws Exception {
        String json = """
            {"messageId":"legacy-1","chatRoomId":"room-1","userId":"u1","username":"alice",
             "content":"c","type":"CHAT","timestamp":"2026-07-04T12:00:00","isAiGenerated":false}""";
        searchService.indexChatMessage(json);
        verify(elasticsearchClient, never()).delete(any(DeleteRequest.class));
    }
}
```

- [ ] **Step 2: Run it, confirm FAIL to compile** (SearchService constructor still takes the repo). Run: `./gradlew :search-service:test --tests 'com.chatflow.search.service.SearchServiceDeleteTest'`.

- [ ] **Step 3: Rewrite SearchService.** Swap the repo field for `ElasticsearchClient`; rewrite `flushBuffer()` and the delete branch. Full new imports + changed members:

```java
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.chatflow.search.util.SearchConstants;
// remove: import ...repository.ChatMessageSearchRepository;
// remove: import org.springframework.data.domain.*;

private final ElasticsearchClient elasticsearchClient;
private final ObjectMapper objectMapper;
// ... buffer, lock, consecutiveFailures unchanged

public SearchService(ElasticsearchClient elasticsearchClient, ObjectMapper objectMapper, MeterRegistry registry) {
    this.elasticsearchClient = elasticsearchClient;
    this.objectMapper = objectMapper;
    Gauge.builder("chatflow.search.buffer.size", buffer, List::size)
            .description("Search indexing buffer size")
            .register(registry);
}
```

Delete branch (inside `indexChatMessage`, replacing `searchRepository.deleteById(...)`):

```java
if (message.isDeleted()) {
    log.info("Removing deleted message {} from ES index", message.getMessageId());
    try {
        elasticsearchClient.delete(d -> d
                .index(SearchConstants.CHAT_MESSAGES_INDEX)
                .id(message.getMessageId()));
    } catch (Exception e) {
        log.error("Failed to delete message {} from ES index", message.getMessageId(), e);
    }
    return;
}
```

`flushBuffer()` (replace `searchRepository.saveAll(batch)` with a bulk request; treat a thrown exception OR `response.errors()` as failure, preserving the existing re-queue/drop logic):

```java
private void flushBuffer() {
    if (buffer.isEmpty()) return;

    List<ChatMessageDocument> batch = new ArrayList<>(buffer);
    buffer.clear();

    try {
        BulkRequest.Builder br = new BulkRequest.Builder();
        for (ChatMessageDocument doc : batch) {
            br.operations(op -> op.index(idx -> idx
                    .index(SearchConstants.CHAT_MESSAGES_INDEX)
                    .id(doc.getId())
                    .document(doc)));
        }
        BulkResponse response = elasticsearchClient.bulk(br.build());
        if (response.errors()) {
            // Item-level failures: log the first few and treat the batch as failed.
            response.items().stream()
                    .filter(i -> i.error() != null)
                    .limit(5)
                    .forEach(i -> log.error("Bulk item failed id={} : {}",
                            i.id(), i.error() != null ? i.error().reason() : "unknown"));
            throw new IllegalStateException("Bulk response contained item errors");
        }
        log.info("Bulk indexed {} messages", batch.size());
        consecutiveFailures = 0;
    } catch (Exception e) {
        consecutiveFailures++;
        if (consecutiveFailures <= MAX_RETRY_COUNT) {
            log.error("Bulk indexing failed ({}/{}), re-queuing {} messages: {}",
                    consecutiveFailures, MAX_RETRY_COUNT, batch.size(), e.getMessage());
            buffer.addAll(0, batch);
        } else {
            log.error("Bulk indexing failed {} consecutive times, dropping {} messages to prevent OOM",
                    consecutiveFailures, batch.size());
            consecutiveFailures = 0;
        }
    }
}
```

Update the class javadoc to note it is now indexing-only (reads live in KoreanSearchService).

- [ ] **Step 4:** Run `./gradlew :search-service:test --tests 'com.chatflow.search.service.SearchServiceDeleteTest'` → PASS.

- [ ] **Step 5: Commit** `refactor(search): rewrite indexing write path to low-level bulk/delete client`.

## Task 3: Delete Spring-Data-ES artifacts (repository, annotations, @EnableElasticsearchRepositories)

**Files:**
- Delete: `search-service/src/main/java/com/chatflow/search/repository/ChatMessageSearchRepository.java`
- Modify: `search-service/src/main/java/com/chatflow/search/SearchServiceApplication.java`
- Modify: `search-service/src/main/java/com/chatflow/search/document/ChatMessageDocument.java`
- Modify: `search-service/src/main/java/com/chatflow/search/document/BlogPostDocument.java`
- Modify: `search-service/src/test/java/com/chatflow/search/controller/FilterEndpointTest.java`

- [ ] **Step 1: Delete** `ChatMessageSearchRepository.java` (no remaining users after Tasks 1–2). Also delete the `repository` package dir if now empty.

- [ ] **Step 2: SearchServiceApplication** — remove `@EnableElasticsearchRepositories` annotation + its import (`org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories`). Keep `@SpringBootApplication`, `@ComponentScan`, `@EnableScheduling`.

- [ ] **Step 3: Strip spring-data annotations from `ChatMessageDocument`.** Remove imports `org.springframework.data.annotation.Id`, `org.springframework.data.elasticsearch.annotations.{Document,Field,FieldType}`; remove `@Document(...)`, all `@Field(...)`, and `@Id`. Keep Lombok (`@Data @Builder @NoArgsConstructor @AllArgsConstructor`), `@JsonIgnoreProperties`, the `SearchConstants` import ONLY if still referenced (it was only used in `@Document` → remove that import too), and every field (unchanged). Result is a plain POJO. (Jackson maps by field name; the low-level client's `JacksonJsonpMapper` handles (de)serialization — behavior-neutral.)

- [ ] **Step 4: Strip spring-data annotations from `BlogPostDocument`** identically (remove `@Document`, `@Field`, `@Id` + their imports; keep Lombok + fields).

- [ ] **Step 5: Fix `FilterEndpointTest`.** Remove the now-dangling mocks/imports: `ChatMessageSearchRepository` (deleted class) and `ElasticsearchOperations` (`@MockBean(name="elasticsearchTemplate")`) — both reference removed Spring-Data types. Also remove the `SearchService` mock (SearchController no longer injects it). Keep `KoreanSearchService` + `KafkaTemplate` mocks + the `TestConfig` MeterRegistry. The 5 `/filter` test methods stay unchanged. Remove imports: `com.chatflow.search.repository.ChatMessageSearchRepository`, `org.springframework.data.elasticsearch.core.ElasticsearchOperations`, `com.chatflow.search.service.SearchService`.

- [ ] **Step 6:** Compile check `./gradlew :search-service:compileJava :search-service:compileTestJava`. Expect a build FAILURE at this point ONLY if the low-level client libs are no longer resolvable — that is resolved in Task 4. If it fails with "package co.elastic.clients... does not exist", proceed to Task 4 before re-running. If it compiles (libs still transitively present pre-starter-removal), continue.

- [ ] **Step 7: Commit** `refactor(search): delete Spring-Data-ES repository, annotations, and @EnableElasticsearchRepositories`.

## Task 4: Swap the build dependency (starter → explicit low-level client, pinned)

**Files:**
- Modify: `search-service/build.gradle`

- [ ] **Step 1: Capture the currently-resolved versions** (so the explicit deps are byte-identical to today's transitive ones). Run:
  `./gradlew :search-service:dependencies --configuration runtimeClasspath | grep -E 'elasticsearch-java|elasticsearch-rest-client'`
  Note the resolved versions (e.g. `co.elastic.clients:elasticsearch-java:8.11.x` and `org.elasticsearch.client:elasticsearch-rest-client:8.11.x`).

- [ ] **Step 2: Edit `build.gradle`.** Replace line 5 (`implementation 'org.springframework.boot:spring-boot-starter-data-elasticsearch'`) with the two explicit deps, PINNED to the versions captured in Step 1:

```gradle
    // Low-level Elasticsearch Java client (formerly pulled transitively by
    // spring-boot-starter-data-elasticsearch, removed with the Spring-Data stack).
    // Pinned to the versions Spring Boot 3.2's BOM resolved for the starter so
    // runtime behavior is unchanged. ElasticsearchClient/RestClient beans live
    // in ElasticsearchConfig.
    implementation 'co.elastic.clients:elasticsearch-java:<captured-version>'
    implementation 'org.elasticsearch.client:elasticsearch-rest-client:<captured-version>'
```
Replace `<captured-version>` with the exact strings from Step 1. If the Spring Boot BOM still manages these without an explicit version once the starter is gone, prefer omitting the version (let the BOM manage it); only pin if `dependencies` shows them unmanaged/unresolved. VERIFY by resolving again.

- [ ] **Step 3: Verify** `./gradlew :search-service:compileJava :search-service:compileTestJava` → BUILD SUCCESSFUL. Then `./gradlew :search-service:test` → all green (KoreanSearchServiceTest 34, SearchServiceDeleteTest, FilterEndpointTest 5, SearchControllerLegacyDelegationTest 7, SearchServiceApplicationTest smoke). If `SearchServiceApplicationTest` (full context) fails on a missing bean, confirm no code still references Spring-Data types.

- [ ] **Step 4: Commit** `build(search): replace spring-boot-starter-data-elasticsearch with explicit low-level client`.

## Task 5: Testcontainers ES integration test for the rewritten indexing path

**Files:**
- Create: `search-service/src/test/java/com/chatflow/search/service/SearchIndexingIntegrationTest.java`

Protects the highest-risk change (Task 2's bulk/delete rewrite) against a REAL Elasticsearch — the write path had no integration coverage before.

- [ ] **Step 1: Write the test.** Round-trips buffer→flush→search and delete against ES 8.11 with security disabled (plain HTTP, no Nori needed — uses a minimal index; the Korean analyzer is separately covered by KoreanSearchServiceTest). Skips cleanly without Docker.

```java
package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.chatflow.search.util.SearchConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class SearchIndexingIntegrationTest {

    @Container
    static final ElasticsearchContainer ES = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.11.3")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    static ElasticsearchClient client;
    static RestClient restClient;
    static SearchService service;

    @BeforeAll
    static void setUp() throws Exception {
        restClient = RestClient.builder(HttpHost.create(ES.getHttpHostAddress())).build();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper(mapper)));
        // Minimal index so mapping is deterministic (standard analyzer; Nori not needed here).
        client.indices().create(c -> c.index(SearchConstants.CHAT_MESSAGES_INDEX)
                .mappings(m -> m
                        .properties("chatRoomId", p -> p.keyword(k -> k))
                        .properties("content", p -> p.text(t -> t))
                        .properties("messageType", p -> p.keyword(k -> k))
                        .properties("timestamp", p -> p.date(d -> d))));
        service = new SearchService(client, mapper, new SimpleMeterRegistry());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (restClient != null) restClient.close();
    }

    private static String msg(String id, boolean deleted) {
        return """
            {"messageId":"%s","chatRoomId":"room-1","userId":"u1","username":"alice",
             "content":"hello world","type":"CHAT","timestamp":"2026-07-04T12:00:00",
             "isDeleted":%s,"isAiGenerated":false}""".formatted(id, deleted);
    }

    private static long count() throws Exception {
        client.indices().refresh(r -> r.index(SearchConstants.CHAT_MESSAGES_INDEX));
        return client.count(c -> c.index(SearchConstants.CHAT_MESSAGES_INDEX)).count();
    }

    @Test
    void bufferFlush_indexesDocs_thenDeleteRemovesOne() throws Exception {
        service.indexChatMessage(msg("it-1", false));
        service.indexChatMessage(msg("it-2", false));
        service.scheduledFlush();               // triggers bulk index
        assertThat(count()).isEqualTo(2L);

        service.indexChatMessage(msg("it-1", true));  // delete path
        assertThat(count()).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: Run** `./gradlew :search-service:test --tests 'com.chatflow.search.service.SearchIndexingIntegrationTest'` (Docker available). Expect PASS (ES image may download on first run). If it fails, that is a REAL write-path bug — report the error, do not paper over.

- [ ] **Step 3: Commit** `test(search): Testcontainers integration test for low-level bulk/delete indexing`.

## Task 6: Whole-branch review + full suite + merge

- [ ] Run full `./gradlew test` — all modules green.
- [ ] Grep confirms zero remaining Spring-Data-ES references: `grep -rn 'springframework.data.elasticsearch\|ElasticsearchRepository\|EnableElasticsearchRepositories\|ElasticsearchOperations' search-service/src` returns nothing (only possibly in comments).
- [ ] Whole-branch code review (fresh reviewer): write-path parity (`.id(doc.getId())`, delete-by-id), the 4 endpoints' delegation correctness + preserved validation/clamping, behavior-change is intentional and documented, no dangling Spring-Data refs, dependency pin correct.
- [ ] Merge to develop (`--no-ff`) + push.

## Self-review notes
- Spec coverage: repository removal (T3), starter removal (T4), annotation stripping (T3), read consolidation (T1), write-path rewrite (T2), integration safety (T5) — all covered.
- Type consistency: `SearchService` constructor `(ElasticsearchClient, ObjectMapper, MeterRegistry)` is used identically in T2's production code, T2's unit test, and T5's integration test. `searchWithFilters(roomId, query, username, startDate, endDate, messageType, pageable)` arg order matches KoreanSearchService's actual signature and FilterEndpointTest's existing mocks.
- Behavior change (substring→analyzer; username term-filter) is called out in Constraints and asserted as *delegation*, not old semantics.
- No placeholders except the deliberate `<captured-version>` in T4, which has an exact capture procedure in T4 Step 1.
```
