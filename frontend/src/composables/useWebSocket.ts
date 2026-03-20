import { ref, onUnmounted } from 'vue'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import type { ChatMessage } from '@/types'
import { useOfflineQueue } from './useOfflineQueue'

export type ConnectionStatus = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING'

const MAX_RECONNECT_ATTEMPTS = 5
const INITIAL_RECONNECT_DELAY = 1000
const MAX_MESSAGES = 500

export function useWebSocket() {
  const connectionStatus = ref<ConnectionStatus>('DISCONNECTED')
  const isConnected = ref(false)
  const client = ref<Client | null>(null)
  const messages = ref<ChatMessage[]>([])
  const { isOnline, queueSize, enqueue, flushQueue } = useOfflineQueue()

  let reconnectAttempts = 0
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let currentRoomId = ''
  let currentUsername = ''

  const connect = (roomId: string, username: string) => {
    currentRoomId = roomId
    currentUsername = username
    reconnectAttempts = 0
    doConnect()
  }

  const doConnect = () => {
    connectionStatus.value = reconnectAttempts > 0 ? 'RECONNECTING' : 'CONNECTING'

    const socket = new SockJS('/ws')

    client.value = new Client({
      webSocketFactory: () => socket,
      onConnect: () => {
        connectionStatus.value = 'CONNECTED'
        isConnected.value = true
        reconnectAttempts = 0

        // 채팅방 구독
        client.value?.subscribe(`/topic/chat/${currentRoomId}`, (message) => {
          try {
            const chatMessage: ChatMessage = JSON.parse(message.body)
            messages.value.push(chatMessage)
            // 메모리 누수 방지: 최대 메시지 수 제한
            while (messages.value.length > MAX_MESSAGES) {
              messages.value.shift()
            }
          } catch (e) {
            console.error('메시지 파싱 오류:', e)
          }
        })

        // 사용자 입장 메시지 전송
        client.value?.publish({
          destination: '/app/chat.addUser',
          body: JSON.stringify({
            chatRoomId: currentRoomId,
            username: currentUsername,
            type: 'JOIN'
          })
        })

        // 오프라인 큐에 쌓인 메시지 전송
        flushQueue((msg) => {
          client.value?.publish({
            destination: '/app/chat.sendMessage',
            body: JSON.stringify(msg)
          })
        })
      },
      onDisconnect: () => {
        connectionStatus.value = 'DISCONNECTED'
        isConnected.value = false
        attemptReconnect()
      },
      onStompError: (frame) => {
        console.error('STOMP 오류:', frame)
        connectionStatus.value = 'DISCONNECTED'
        isConnected.value = false
        attemptReconnect()
      },
      onWebSocketError: () => {
        console.error('WebSocket 연결 오류')
      }
    })

    client.value.activate()
  }

  const attemptReconnect = () => {
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS || !currentRoomId) {
      console.warn('최대 재연결 시도 횟수 초과')
      return
    }

    reconnectAttempts++
    const delay = INITIAL_RECONNECT_DELAY * Math.pow(2, reconnectAttempts - 1)

    reconnectTimer = setTimeout(() => {
      if (!isConnected.value && currentRoomId) {
        doConnect()
      }
    }, delay)
  }

  const disconnect = () => {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    reconnectAttempts = MAX_RECONNECT_ATTEMPTS // 재연결 방지
    currentRoomId = ''
    currentUsername = ''

    if (client.value) {
      client.value.deactivate()
      client.value = null
    }
    connectionStatus.value = 'DISCONNECTED'
    isConnected.value = false
    messages.value = []
  }

  const sendMessage = (message: Partial<ChatMessage>) => {
    if (client.value && isConnected.value) {
      client.value.publish({
        destination: '/app/chat.sendMessage',
        body: JSON.stringify(message)
      })
    } else {
      // 오프라인 시 큐에 저장, 재연결 후 자동 전송
      enqueue(message)
    }
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    isConnected,
    isOnline,
    connectionStatus,
    messages,
    queueSize,
    connect,
    disconnect,
    sendMessage
  }
}
