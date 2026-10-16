package com.chatflow.search.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RoomMembershipClientTest {

    private static final String BASE = "http://chat-service:8080";

    private MockRestServiceServer server;
    private RoomMembershipClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RoomMembershipClient(builder.build(), "s3cret");
    }

    @Test
    void isMember_204_true_andSendsSecret() {
        server.expect(requestTo(BASE + "/internal/rooms/r1/members/u1"))
                .andExpect(header("X-Gateway-Secret", "s3cret"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(client.isMember("r1", "u1")).isTrue();
        server.verify();
    }

    @Test
    void isMember_404_false() {
        server.expect(requestTo(BASE + "/internal/rooms/r1/members/u1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.isMember("r1", "u1")).isFalse();
    }

    @Test
    void isMember_serverError_throwsUnavailable() {
        server.expect(requestTo(BASE + "/internal/rooms/r1/members/u1"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.isMember("r1", "u1"))
                .isInstanceOf(MembershipCheckUnavailableException.class);
    }

    @Test
    void isMember_forbidden_throwsUnavailable() {
        server.expect(requestTo(BASE + "/internal/rooms/r1/members/u1"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.isMember("r1", "u1"))
                .isInstanceOf(MembershipCheckUnavailableException.class);
    }

    @Test
    void memberRoomIds_returnsList() {
        server.expect(requestTo(BASE + "/internal/rooms/members/u1/room-ids"))
                .andExpect(header("X-Gateway-Secret", "s3cret"))
                .andRespond(withSuccess("[\"r1\",\"r2\"]", MediaType.APPLICATION_JSON));

        assertThat(client.memberRoomIds("u1")).containsExactly("r1", "r2");
    }

    @Test
    void memberRoomIds_error_throwsUnavailable() {
        server.expect(requestTo(BASE + "/internal/rooms/members/u1/room-ids"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.memberRoomIds("u1"))
                .isInstanceOf(MembershipCheckUnavailableException.class);
    }

    @Test
    void memberRoomIds_emptyBody_emptyList() {
        server.expect(requestTo(BASE + "/internal/rooms/members/u1/room-ids"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.memberRoomIds("u1")).isEqualTo(List.of());
    }
}
