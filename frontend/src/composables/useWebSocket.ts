import { ref, onUnmounted } from 'vue'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import type { ChatMessage } from '@/types'

export type ConnectionStatus = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING'

const MAX_RECONNECT_ATTEMPTS = 10
const INITIAL_RECONNECT_DELAY = 1000
const MAX_MESSAGES = 500

export function useWebSocket() {
  const connectionStatus = ref<ConnectionStatus>('DISCONNECTED')
  const isConnected = ref(false)
  const client = ref<Client | null>(null)
  const messages = ref<ChatMessage[]>([])

  let reconnectAttempts = 0
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let currentRoomId = ''
  let currentUsername = ''
  let manualDisconnect = false

  const connect = (roomId: string, username: string) => {
    // 이전 연결 정리
    if (client.value) {
      manualDisconnect = true
      client.value.deactivate()
      client.value = null
    }

    currentRoomId = roomId
    currentUsername = username
    reconnectAttempts = 0
    manualDisconnect = false
    messages.value = []
    doConnect()
  }

  const doConnect = () => {
    if (!currentRoomId || !currentUsername) return

    connectionStatus.value = reconnectAttempts > 0 ? 'RECONNECTING' : 'CONNECTING'

    try {
      const socket = new SockJS('/ws')

      const stompClient = new Client({
        webSocketFactory: () => socket,
        // 하트비트: 10초 간격으로 연결 상태 확인
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000,
        reconnectDelay: 0, // 자체 재연결 로직 사용

        onConnect: () => {
          connectionStatus.value = 'CONNECTED'
          isConnected.value = true
          reconnectAttempts = 0

          // 채팅방 구독
          stompClient.subscribe(`/topic/chat/${currentRoomId}`, (message) => {
            try {
              const chatMessage: ChatMessage = JSON.parse(message.body)
              messages.value.push(chatMessage)
              while (messages.value.length > MAX_MESSAGES) {
                messages.value.shift()
              }
            } catch (e) {
              console.error('메시지 파싱 오류:', e)
            }
          })

          // 에러 토픽 구독
          stompClient.subscribe(`/topic/chat/${currentRoomId}/errors`, (message) => {
            console.warn('서버 에러:', message.body)
          })

          // 사용자 입장 메시지
          stompClient.publish({
            destination: '/app/chat.addUser',
            body: JSON.stringify({
              chatRoomId: currentRoomId,
              username: currentUsername,
              type: 'JOIN',
              timestamp: new Date().toISOString()
            })
          })
        },

        onDisconnect: () => {
          connectionStatus.value = 'DISCONNECTED'
          isConnected.value = false
          if (!manualDisconnect) {
            attemptReconnect()
          }
        },

        onStompError: (frame) => {
          console.error('STOMP 오류:', frame.headers?.message)
          connectionStatus.value = 'DISCONNECTED'
          isConnected.value = false
          if (!manualDisconnect) {
            attemptReconnect()
          }
        },

        onWebSocketError: () => {
          console.error('WebSocket 연결 오류')
        },

        onWebSocketClose: () => {
          if (!manualDisconnect && isConnected.value) {
            isConnected.value = false
            connectionStatus.value = 'DISCONNECTED'
            attemptReconnect()
          }
        }
      })

      client.value = stompClient
      stompClient.activate()
    } catch (e) {
      console.error('WebSocket 초기화 오류:', e)
      attemptReconnect()
    }
  }

  const attemptReconnect = () => {
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS || !currentRoomId || manualDisconnect) {
      if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        console.warn('최대 재연결 시도 횟수 초과')
      }
      return
    }

    reconnectAttempts++
    const delay = Math.min(
      INITIAL_RECONNECT_DELAY * Math.pow(2, reconnectAttempts - 1),
      30000
    )

    connectionStatus.value = 'RECONNECTING'

    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = setTimeout(() => {
      if (!isConnected.value && currentRoomId && !manualDisconnect) {
        doConnect()
      }
    }, delay)
  }

  const disconnect = () => {
    manualDisconnect = true
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    currentRoomId = ''
    currentUsername = ''

    if (client.value) {
      try {
        client.value.deactivate()
      } catch { /* ignore */ }
      client.value = null
    }
    connectionStatus.value = 'DISCONNECTED'
    isConnected.value = false
    messages.value = []
  }

  const sendMessage = (message: Partial<ChatMessage>) => {
    if (client.value?.connected && isConnected.value) {
      try {
        client.value.publish({
          destination: '/app/chat.sendMessage',
          body: JSON.stringify(message)
        })
      } catch (e) {
        console.error('메시지 전송 오류:', e)
        // 연결 끊어진 경우 재연결 시도
        isConnected.value = false
        attemptReconnect()
      }
    } else {
      console.warn('WebSocket 미연결 상태. 재연결 시도...')
      if (!manualDisconnect && currentRoomId) {
        attemptReconnect()
      }
    }
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    isConnected,
    connectionStatus,
    messages,
    connect,
    disconnect,
    sendMessage
  }
}
