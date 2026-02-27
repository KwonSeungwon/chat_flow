import { ref, onUnmounted } from 'vue'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import type { ChatMessage } from '@/types'

export type ConnectionStatus = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING'

const MAX_RECONNECT_ATTEMPTS = 5
const INITIAL_RECONNECT_DELAY = 1000

export function useWebSocket() {
  const connectionStatus = ref<ConnectionStatus>('DISCONNECTED')
  const isConnected = ref(false)
  const client = ref<Client | null>(null)
  const messages = ref<ChatMessage[]>([])

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
        console.log('WebSocket 연결됨')

        // 채팅방 구독
        client.value?.subscribe(`/topic/chat/${currentRoomId}`, (message) => {
          try {
            const chatMessage: ChatMessage = JSON.parse(message.body)
            messages.value.push(chatMessage)
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
      },
      onDisconnect: () => {
        connectionStatus.value = 'DISCONNECTED'
        isConnected.value = false
        console.log('WebSocket 연결 해제됨')
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
      console.log('최대 재연결 시도 횟수 초과')
      return
    }

    reconnectAttempts++
    const delay = INITIAL_RECONNECT_DELAY * Math.pow(2, reconnectAttempts - 1)
    console.log(`${delay}ms 후 재연결 시도 (${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS})`)

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
