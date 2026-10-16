package com.chatflow.chat.repository;

import com.chatflow.chat.entity.ChatRoom;
import com.chatflow.chat.entity.RoomType;
import com.chatflow.chat.entity.RoomTypeConverter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** An old pod may still write HANDOFF during a rolling update; the new pod must read it. */
@DataJpaTest
@ContextConfiguration(classes = RepositoryTestConfig.class)
@ActiveProfiles("test")
class ChatRoomLegacyRoomTypeTest {

    @Autowired private ChatRoomRepository chatRoomRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void handoffRow_loadsAsGeneral_andStaysInRoomList() {
        entityManager.createNativeQuery("INSERT INTO chat_rooms "
                        + "(id, name, room_type, is_private, allow_invites, created_at) "
                        + "VALUES ('r-legacy', 'Ward', 'HANDOFF', FALSE, TRUE, CURRENT_TIMESTAMP)")
                .executeUpdate();
        entityManager.clear();

        ChatRoom room = chatRoomRepository.findById("r-legacy").orElseThrow();
        assertThat(room.getRoomType()).isEqualTo(RoomType.GENERAL);

        List<String> visible = chatRoomRepository.findVisibleOrderByLastActivity("stranger")
                .stream().map(ChatRoom::getId).toList();
        assertThat(visible).contains("r-legacy");
    }

    @Test
    void converter_writesEnumName_andReadsKnownValues() {
        RoomTypeConverter converter = new RoomTypeConverter();
        assertThat(converter.convertToDatabaseColumn(RoomType.DIRECT)).isEqualTo("DIRECT");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute("DIRECT")).isEqualTo(RoomType.DIRECT);
        assertThat(converter.convertToEntityAttribute("HANDOFF")).isEqualTo(RoomType.GENERAL);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
