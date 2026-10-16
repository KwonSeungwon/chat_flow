package com.chatflow.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.chatflow.search.document.ChatMessageDocument;
import com.chatflow.search.util.SearchConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.listener.BatchListenerFailedException;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchService — batch listener, ack-after-bulk indexing")
class SearchServiceTest {

    @Mock private ElasticsearchClient elasticsearchClient;
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        searchService = new SearchService(elasticsearchClient, objectMapper);
    }

    private static String msg(String id, boolean deleted, String type) {
        return """
            {"messageId":"%s","chatRoomId":"room-1","userId":"u1","username":"alice",
             "content":"c","type":"%s","timestamp":"2026-07-04T12:00:00",
             "isDeleted":%s,"isAiGenerated":false}""".formatted(id, type, deleted);
    }

    private BulkRequest captureSingleBulk() throws IOException {
        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(elasticsearchClient).bulk(captor.capture());
        return captor.getValue();
    }

    private void stubBulkOk() throws IOException {
        BulkResponse ok = mock(BulkResponse.class);
        when(ok.errors()).thenReturn(false);
        when(elasticsearchClient.bulk(any(BulkRequest.class))).thenReturn(ok);
    }

    @Test
    @DisplayName("bulk transport failure is rethrown so the container retries and dead-letters")
    void batch_bulkFailure_propagatesForRetry() throws Exception {
        when(elasticsearchClient.bulk(any(BulkRequest.class))).thenThrow(new IOException("ES down"));

        assertThatThrownBy(() -> searchService.indexChatMessages(List.of(msg("m1", false, "CHAT"))))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("bulk item error is rethrown pointing at the failed record so retry resumes there")
    void batch_bulkItemError_failsAtRecordIndex() throws Exception {
        BulkResponseItem okItem = mock(BulkResponseItem.class);
        BulkResponseItem badItem = mock(BulkResponseItem.class);
        when(badItem.error()).thenReturn(ErrorCause.of(e -> e.type("mapper_parsing_exception").reason("bad")));
        BulkResponse response = mock(BulkResponse.class);
        when(response.errors()).thenReturn(true);
        when(response.items()).thenReturn(List.of(okItem, badItem));
        when(elasticsearchClient.bulk(any(BulkRequest.class))).thenReturn(response);

        // record 1 (JOIN) produces no operation, so the 2nd operation maps to record 2
        List<String> batch = List.of(msg("m1", false, "CHAT"), msg("j1", false, "JOIN"), msg("m2", false, "CHAT"));

        assertThatThrownBy(() -> searchService.indexChatMessages(batch))
                .isInstanceOfSatisfying(BatchListenerFailedException.class,
                        e -> assertThat(e.getIndex()).isEqualTo(2));
    }

    @Test
    @DisplayName("index then delete of the same message in one batch are written in arrival order")
    void batch_deleteAfterIndexInSameBatch_documentNotResurrected() throws Exception {
        stubBulkOk();

        searchService.indexChatMessages(List.of(msg("m1", false, "CHAT"), msg("m1", true, "CHAT")));

        List<BulkOperation> ops = captureSingleBulk().operations();
        assertThat(ops).hasSize(2);
        assertThat(ops.get(0)._kind()).isEqualTo(BulkOperation.Kind.Index);
        assertThat(ops.get(0).index().id()).isEqualTo("m1");
        assertThat(ops.get(1)._kind()).isEqualTo(BulkOperation.Kind.Delete);
        assertThat(ops.get(1).delete().id()).isEqualTo("m1");
        assertThat(ops.get(1).delete().index()).isEqualTo(SearchConstants.CHAT_MESSAGES_INDEX);
        // the delete must not bypass the ordered bulk
        verify(elasticsearchClient, never()).delete(any(DeleteRequest.class));
    }

    @Test
    @DisplayName("message without a type is indexed without NPE")
    void batch_nullType_noNpe() throws Exception {
        stubBulkOk();
        String noType = """
            {"messageId":"nt-1","chatRoomId":"room-1","userId":"u1","username":"alice",
             "content":"c","timestamp":"2026-07-04T12:00:00"}""";

        assertThatCode(() -> searchService.indexChatMessages(List.of(noType))).doesNotThrowAnyException();

        BulkOperation op = captureSingleBulk().operations().get(0);
        assertThat(op.index().id()).isEqualTo("nt-1");
        assertThat(((ChatMessageDocument) op.index().document()).getMessageType()).isNull();
    }

    @Test
    @DisplayName("JOIN/LEAVE/SYSTEM messages are not indexed")
    void batch_systemMessages_skipped() throws Exception {
        searchService.indexChatMessages(List.of(
                msg("j", false, "JOIN"), msg("l", false, "LEAVE"), msg("s", false, "SYSTEM")));

        verifyNoInteractions(elasticsearchClient);
    }

    @Test
    @DisplayName("deleted JOIN message still produces a delete")
    void batch_deletedJoin_stillDeletes() throws Exception {
        stubBulkOk();

        searchService.indexChatMessages(List.of(msg("jd", true, "JOIN")));

        BulkOperation op = captureSingleBulk().operations().get(0);
        assertThat(op._kind()).isEqualTo(BulkOperation.Kind.Delete);
        assertThat(op.delete().id()).isEqualTo("jd");
    }

    @Test
    @DisplayName("malformed record: earlier records are written, then the failure points at the bad record")
    void batch_malformedRecord_writesPrefixThenFailsAtIndex() throws Exception {
        stubBulkOk();
        List<String> batch = List.of(msg("m1", false, "CHAT"), "{not json", msg("m2", false, "CHAT"));

        // the Jackson exception stays the cause so the shared error handler dead-letters without retry
        assertThatThrownBy(() -> searchService.indexChatMessages(batch))
                .isInstanceOfSatisfying(BatchListenerFailedException.class,
                        e -> assertThat(e.getIndex()).isEqualTo(1))
                .hasCauseInstanceOf(JsonProcessingException.class);

        List<BulkOperation> ops = captureSingleBulk().operations();
        assertThat(ops).hasSize(1);
        assertThat(ops.get(0).index().id()).isEqualTo("m1");
    }

    @Test
    @DisplayName("indexed document carries isAiGenerated, forwardedFrom and edited")
    void batch_documentCarriesAllFields() throws Exception {
        stubBulkOk();
        String json = """
            {"messageId":"f1","chatRoomId":"room-1","userId":"u1","username":"alice",
             "content":"c","type":"CHAT","timestamp":"2026-07-04T12:00:00",
             "isAiGenerated":true,"edited":true,"forwardedFrom":"bob: hi"}""";

        searchService.indexChatMessages(List.of(json));

        ChatMessageDocument doc = (ChatMessageDocument) captureSingleBulk().operations().get(0).index().document();
        assertThat(doc.isAiGenerated()).isTrue();
        assertThat(doc.isEdited()).isTrue();
        assertThat(doc.getForwardedFrom()).isEqualTo("bob: hi");
    }

    @Test
    @DisplayName("ChatMessageDocument serializes the AI flag as isAiGenerated (matches index mapping)")
    void document_serializesIsAiGeneratedWireName() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(ChatMessageDocument.builder().id("x").isAiGenerated(true).build());

        assertThat(json).contains("\"isAiGenerated\":true").doesNotContain("\"aiGenerated\"");
        assertThat(mapper.readValue(json, ChatMessageDocument.class).isAiGenerated()).isTrue();
    }

    @Test
    @DisplayName("a record without messageId is skipped instead of poisoning the batch")
    void batch_missingMessageId_skipped() throws Exception {
        String json = """
            {"chatRoomId":"room-1","username":"alice","content":"c","type":"CHAT","isDeleted":true}""";

        assertThatCode(() -> searchService.indexChatMessages(List.of(json))).doesNotThrowAnyException();
        verifyNoInteractions(elasticsearchClient);
    }
}
