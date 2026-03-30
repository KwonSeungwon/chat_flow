export type MessageType = 'CHAT' | 'JOIN' | 'LEAVE' | 'SYSTEM' | 'AI_SUMMARY'

export interface ChatMessage {
  id?: string
  messageId?: string
  chatRoomId: string
  userId: string
  username: string
  content: string
  timestamp: string
  type: MessageType
  isAiGenerated?: boolean
  messageType?: string
}

export interface ChatRoom {
  id: string
  name: string
  description?: string
  color?: string
  externalId?: string
  isPrivate?: boolean
  allowInvites?: boolean
  participantCount: number
  maxParticipants?: number
  createdAt: string
}
