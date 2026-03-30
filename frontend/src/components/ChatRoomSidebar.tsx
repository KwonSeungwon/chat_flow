import { useState, useEffect, useCallback } from 'react'
import api from '@/utils/api'
import type { ChatRoom } from '@/types'

interface Props {
  currentRoomId: string
  onSelectRoom: (roomId: string) => void
}

const COLORS = ['#6366f1','#3b82f6','#06b6d4','#10b981','#84cc16','#eab308','#f97316','#ef4444','#ec4899','#8b5cf6']
const DEFAULT_ROOMS: ChatRoom[] = [
  { id: 'general', name: '일반', participantCount: 0, createdAt: '', color: '#6366f1' },
  { id: 'tech', name: '기술 토론', participantCount: 0, createdAt: '', color: '#10b981' },
  { id: 'random', name: '자유 토론', participantCount: 0, createdAt: '', color: '#f97316' }
]

export default function ChatRoomSidebar({ currentRoomId, onSelectRoom }: Props) {
  const [rooms, setRooms] = useState<ChatRoom[]>([])
  const [showCreate, setShowCreate] = useState(false)
  const [name, setName] = useState('')
  const [desc, setDesc] = useState('')
  const [color, setColor] = useState(COLORS[0])
  const [creating, setCreating] = useState(false)

  const fetchRooms = useCallback(async () => {
    try {
      const res = await api.get('/api/chat/rooms')
      setRooms(res.data.data || res.data || [])
    } catch {
      setRooms(DEFAULT_ROOMS)
    }
  }, [])

  useEffect(() => { fetchRooms() }, [fetchRooms])

  const handleCreate = async () => {
    if (name.trim().length < 2 || creating) return
    setCreating(true)
    try {
      const res = await api.post('/api/chat/rooms', { name: name.trim(), description: desc, color })
      const created = res.data.data || res.data
      setRooms(prev => [created, ...prev])
      onSelectRoom(created.id)
      setShowCreate(false)
      setName(''); setDesc(''); setColor(COLORS[0])
    } catch { /* */ }
    finally { setCreating(false) }
  }

  return (
    <div className="d-flex flex-column h-100" style={{ background: 'var(--bs-body-bg)', minWidth: 240 }}>
      <div className="p-3 border-bottom">
        <h6 className="mb-0"><i className="bi bi-chat-dots me-2" />채팅방</h6>
      </div>

      <div className="flex-grow-1 overflow-auto">
        {rooms.map(room => (
          <div key={room.id} className={`p-3 border-bottom d-flex align-items-center gap-2 ${currentRoomId === room.id ? 'bg-primary text-white' : ''}`}
            style={{ cursor: 'pointer' }} onClick={() => onSelectRoom(room.id)}>
            <span className="rounded-circle flex-shrink-0" style={{ width: 12, height: 12, background: room.color || '#6366f1' }} />
            <div className="flex-grow-1 text-truncate">
              <div className="fw-semibold" style={{ fontSize: '0.9rem' }}>{room.name}</div>
              <small className={currentRoomId === room.id ? 'text-white-50' : 'text-muted'}>{room.participantCount || 0}명{room.maxParticipants ? `/${room.maxParticipants}` : ''}</small>
            </div>
          </div>
        ))}
      </div>

      <div className="p-3 border-top">
        <button className="btn btn-primary w-100" onClick={() => setShowCreate(true)}>
          <i className="bi bi-plus-circle me-2" />새 채팅방
        </button>
      </div>

      {showCreate && (
        <div className="position-fixed top-0 start-0 w-100 h-100 d-flex align-items-center justify-content-center" style={{ background: 'rgba(0,0,0,0.5)', zIndex: 2000 }} onClick={() => setShowCreate(false)}>
          <div className="card shadow-lg" style={{ maxWidth: 420, width: '100%', margin: 16 }} onClick={e => e.stopPropagation()}>
            <div className="card-header bg-primary text-white d-flex justify-content-between align-items-center">
              <strong>새 채팅방 만들기</strong>
              <button className="btn-close btn-close-white" onClick={() => setShowCreate(false)} />
            </div>
            <div className="card-body">
              <div className="mb-3">
                <label className="form-label small fw-semibold">채팅방 이름</label>
                <input className="form-control" value={name} onChange={e => setName(e.target.value)} placeholder="이름 (2글자 이상)" maxLength={50} autoFocus />
              </div>
              <div className="mb-3">
                <label className="form-label small fw-semibold">설명 (선택)</label>
                <textarea className="form-control" value={desc} onChange={e => setDesc(e.target.value)} rows={2} maxLength={200} />
              </div>
              <div className="mb-3">
                <label className="form-label small fw-semibold">색상</label>
                <div className="d-flex gap-2 flex-wrap">
                  {COLORS.map(c => (
                    <div key={c} className="rounded-circle d-flex align-items-center justify-content-center" onClick={() => setColor(c)}
                      style={{ width: 32, height: 32, background: c, cursor: 'pointer', border: color === c ? '3px solid #333' : '3px solid transparent', color: 'white' }}>
                      {color === c && <i className="bi bi-check" />}
                    </div>
                  ))}
                </div>
              </div>
            </div>
            <div className="card-footer d-flex gap-2 justify-content-end">
              <button className="btn btn-secondary" onClick={() => setShowCreate(false)}>취소</button>
              <button className="btn btn-primary" disabled={name.trim().length < 2 || creating} onClick={handleCreate}>
                {creating ? <span className="spinner-border spinner-border-sm" /> : '생성'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
