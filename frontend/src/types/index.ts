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
}

export enum MessageType {
  CHAT = 'CHAT',
  JOIN = 'JOIN',
  LEAVE = 'LEAVE',
  SYSTEM = 'SYSTEM',
  AI_SUMMARY = 'AI_SUMMARY'
}

export interface ChatRoom {
  id: string
  name: string
  description?: string
  participants: number
  createdAt: string
}

export interface SearchResult {
  content: {
    id: string
    messageId: string
    chatRoomId: string
    userId: string
    username: string
    content: string
    timestamp: string
    messageType: string
    isAiGenerated: boolean
  }[]
  totalElements: number
  totalPages: number
  size: number
  number: number
}

export interface User {
  id: string
  username: string
  avatar?: string
}