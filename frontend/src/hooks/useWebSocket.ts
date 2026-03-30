import { useRef, useState, useCallback, useEffect } from 'react'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import type { ChatMessage } from '@/types'

const MAX_RECONNECT = 10
const MAX_MESSAGES = 500

export function useWebSocket() {
  const [isConnected, setIsConnected] = useState(false)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const clientRef = useRef<Client | null>(null)
  const roomRef = useRef('')
  const userRef = useRef('')
  const manualRef = useRef(false)
  const attemptsRef = useRef(0)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const doConnect = useCallback(() => {
    if (!roomRef.current || !userRef.current) return

    try {
      const socket = new SockJS('/ws')
      const stomp = new Client({
        webSocketFactory: () => socket,
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000,
        reconnectDelay: 0,

        onConnect: () => {
          setIsConnected(true)
          attemptsRef.current = 0

          stomp.subscribe(`/topic/chat/${roomRef.current}`, (msg) => {
            try {
              const parsed: ChatMessage = JSON.parse(msg.body)
              setMessages(prev => {
                const next = [...prev, parsed]
                return next.length > MAX_MESSAGES ? next.slice(-MAX_MESSAGES) : next
              })
            } catch { /* ignore */ }
          })

          stomp.subscribe(`/topic/chat/${roomRef.current}/errors`, (msg) => {
            console.warn('Server error:', msg.body)
          })

          stomp.publish({
            destination: '/app/chat.addUser',
            body: JSON.stringify({
              chatRoomId: roomRef.current,
              username: userRef.current,
              type: 'JOIN',
              timestamp: new Date().toISOString()
            })
          })
        },

        onDisconnect: () => {
          setIsConnected(false)
          if (!manualRef.current) attemptReconnect()
        },

        onStompError: () => {
          setIsConnected(false)
          if (!manualRef.current) attemptReconnect()
        },

        onWebSocketClose: () => {
          setIsConnected(false)
          if (!manualRef.current) attemptReconnect()
        }
      })

      clientRef.current = stomp
      stomp.activate()
    } catch {
      attemptReconnect()
    }
  }, [])

  const attemptReconnect = useCallback(() => {
    if (attemptsRef.current >= MAX_RECONNECT || !roomRef.current || manualRef.current) return
    attemptsRef.current++
    const delay = Math.min(1000 * Math.pow(2, attemptsRef.current - 1), 30000)
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => {
      if (!manualRef.current && roomRef.current) doConnect()
    }, delay)
  }, [doConnect])

  const connect = useCallback((roomId: string, username: string) => {
    manualRef.current = true
    if (clientRef.current) {
      try { clientRef.current.deactivate() } catch { /* */ }
      clientRef.current = null
    }
    roomRef.current = roomId
    userRef.current = username
    manualRef.current = false
    attemptsRef.current = 0
    setMessages([])
    doConnect()
  }, [doConnect])

  const disconnect = useCallback(() => {
    manualRef.current = true
    if (timerRef.current) { clearTimeout(timerRef.current); timerRef.current = null }
    roomRef.current = ''
    userRef.current = ''
    if (clientRef.current) {
      try { clientRef.current.deactivate() } catch { /* */ }
      clientRef.current = null
    }
    setIsConnected(false)
    setMessages([])
  }, [])

  const sendMessage = useCallback((message: Partial<ChatMessage>) => {
    if (clientRef.current?.connected) {
      clientRef.current.publish({
        destination: '/app/chat.sendMessage',
        body: JSON.stringify(message)
      })
    }
  }, [])

  useEffect(() => {
    return () => { disconnect() }
  }, [disconnect])

  return { isConnected, messages, connect, disconnect, sendMessage, setMessages }
}
