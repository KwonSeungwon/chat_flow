import { ref, onMounted, onUnmounted } from 'vue'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import type { ChatMessage } from '@/types'

export function useWebSocket() {
  const isConnected = ref(false)
  const client = ref<Client | null>(null)
  const messages = ref<ChatMessage[]>([])

  const connect = (roomId: string, username: string) => {
    const socket = new SockJS('/ws')
    
    client.value = new Client({
      webSocketFactory: () => socket,
      onConnect: () => {
        isConnected.value = true
        console.log('WebSocket 연결됨')
        
        // 채팅방 구독
        client.value?.subscribe(`/topic/chat/${roomId}`, (message) => {
          const chatMessage: ChatMessage = JSON.parse(message.body)
          messages.value.push(chatMessage)
        })

        // 사용자 입장 메시지 전송
        client.value?.publish({
          destination: '/app/chat.addUser',
          body: JSON.stringify({
            chatRoomId: roomId,
            username: username,
            type: 'JOIN'
          })
        })
      },
      onDisconnect: () => {
        isConnected.value = false
        console.log('WebSocket 연결 해제됨')
      },
      onStompError: (frame) => {
        console.error('STOMP 오류:', frame)
      }
    })

    client.value.activate()
  }

  const disconnect = () => {
    if (client.value) {
      client.value.deactivate()
      client.value = null
    }
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
    messages,
    connect,
    disconnect,
    sendMessage
  }
}