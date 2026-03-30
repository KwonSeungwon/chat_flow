import { useState, useEffect, useCallback, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/authStore'
import { useWebSocket } from '@/hooks/useWebSocket'
import api from '@/utils/api'
import ChatRoomSidebar from '@/components/ChatRoomSidebar'
import ChatMessages from '@/components/ChatMessages'
import ChatInput from '@/components/ChatInput'
import type { ChatMessage } from '@/types'

export default function ChatPage() {
  const { roomId: paramRoomId } = useParams()
  const navigate = useNavigate()
  const { username, userId, isGuest, logout } = useAuthStore()
  const { isConnected, messages, connect, disconnect, sendMessage } = useWebSocket()

  const [currentRoomId, setCurrentRoomId] = useState(paramRoomId || 'general')
  const [history, setHistory] = useState<ChatMessage[]>([])
  const [loadingHistory, setLoadingHistory] = useState(false)
  const [showSidebar, setShowSidebar] = useState(false)

  const allMessages = useMemo(() => {
    const filtered = history.filter(h => !messages.some(m => m.messageId === h.messageId))
    return [...filtered, ...messages]
  }, [history, messages])

  const loadHistory = useCallback(async (roomId: string) => {
    setLoadingHistory(true)
    try {
      const res = await api.get(`/api/chat/rooms/${roomId}/messages?size=50`)
      const page = res.data.data
      const items = (page.content || []).map((m: any) => ({
        ...m, type: m.type || m.messageType, isAiGenerated: m.aiGenerated || m.isAiGenerated || false
      }))
      setHistory(items.reverse())
    } catch { setHistory([]) }
    finally { setLoadingHistory(false) }
  }, [])

  const joinRoom = useCallback((roomId: string) => {
    setCurrentRoomId(roomId)
    setShowSidebar(false)
    navigate(`/chat/${roomId}`)
    disconnect()
    setHistory([])
    loadHistory(roomId)
    connect(roomId, username)
  }, [disconnect, connect, loadHistory, navigate, username])

  const handleSend = useCallback((content: string) => {
    sendMessage({
      chatRoomId: currentRoomId,
      userId: userId || `user_${Date.now()}`,
      username,
      content,
      type: 'CHAT',
      timestamp: new Date().toISOString()
    })
  }, [sendMessage, currentRoomId, userId, username])

  const handleLogout = useCallback(() => {
    disconnect()
    logout()
    navigate('/login')
  }, [disconnect, logout, navigate])

  useEffect(() => {
    if (currentRoomId && username) {
      loadHistory(currentRoomId)
      connect(currentRoomId, username)
    }
    return () => { disconnect() }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (paramRoomId && paramRoomId !== currentRoomId) {
      joinRoom(paramRoomId)
    }
  }, [paramRoomId]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="d-flex h-100" style={{ overflow: 'hidden' }}>
      {/* Mobile sidebar overlay */}
      {showSidebar && (
        <div className="position-fixed top-0 start-0 w-100 h-100" style={{ background: 'rgba(0,0,0,0.5)', zIndex: 1050 }} onClick={() => setShowSidebar(false)}>
          <div className="h-100" style={{ width: 280, background: 'var(--bs-body-bg)', boxShadow: '2px 0 8px rgba(0,0,0,0.2)' }} onClick={e => e.stopPropagation()}>
            <ChatRoomSidebar currentRoomId={currentRoomId} onSelectRoom={joinRoom} />
          </div>
        </div>
      )}

      {/* Desktop sidebar */}
      <div className="d-none d-md-block border-end" style={{ width: 240, flexShrink: 0 }}>
        <ChatRoomSidebar currentRoomId={currentRoomId} onSelectRoom={joinRoom} />
      </div>

      {/* Main chat area */}
      <div className="flex-grow-1 d-flex flex-column" style={{ minWidth: 0, minHeight: 0 }}>
        {/* Header */}
        <div className="border-bottom px-2 py-2 d-flex justify-content-between align-items-center bg-light" style={{ flexShrink: 0 }}>
          <div className="d-flex align-items-center gap-2" style={{ minWidth: 0 }}>
            <button className="btn btn-sm btn-outline-secondary d-md-none" onClick={() => setShowSidebar(true)}>
              <i className="bi bi-list" />
            </button>
            <h6 className="mb-0 text-truncate"><i className="bi bi-hash" />{currentRoomId}</h6>
            <span className={`badge rounded-pill ${isConnected ? 'bg-success' : 'bg-danger'}`} style={{ fontSize: '0.65em' }}>
              {isConnected ? '연결' : '끊김'}
            </span>
          </div>
          <div className="dropdown flex-shrink-0">
            <button className="btn btn-outline-secondary btn-sm dropdown-toggle d-flex align-items-center gap-1" data-bs-toggle="dropdown">
              <span className="rounded-circle bg-primary text-white d-inline-flex align-items-center justify-content-center" style={{ width: 22, height: 22, fontSize: '0.55rem', fontWeight: 'bold' }}>
                {username.substring(0, 2).toUpperCase()}
              </span>
              <span className="d-none d-sm-inline text-truncate" style={{ maxWidth: 80 }}>{username}</span>
            </button>
            <ul className="dropdown-menu dropdown-menu-end">
              <li><span className="dropdown-item-text small text-muted"><i className="bi bi-person me-1" />{username}{isGuest && <span className="badge bg-secondary ms-1" style={{ fontSize: '0.7em' }}>게스트</span>}</span></li>
              {isGuest && <li><a className="dropdown-item" href="/login"><i className="bi bi-box-arrow-in-right me-2" />로그인</a></li>}
              <li><hr className="dropdown-divider" /></li>
              <li><a className="dropdown-item text-danger" href="#" onClick={e => { e.preventDefault(); handleLogout() }}><i className="bi bi-box-arrow-left me-2" />로그아웃</a></li>
            </ul>
          </div>
        </div>

        {/* Messages */}
        <ChatMessages messages={allMessages} currentUser={username} loading={loadingHistory} />

        {/* Input */}
        {isConnected && <ChatInput onSend={handleSend} disabled={!isConnected} />}
      </div>
    </div>
  )
}
