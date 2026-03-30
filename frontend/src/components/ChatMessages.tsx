import { useEffect, useRef } from 'react'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import type { ChatMessage } from '@/types'

dayjs.extend(utc)

interface Props {
  messages: ChatMessage[]
  currentUser: string
  loading?: boolean
}

const formatTime = (ts: string) => dayjs.utc(ts).utcOffset(9).format('HH:mm')

export default function ChatMessages({ messages, currentUser, loading }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages.length])

  const isMine = (msg: ChatMessage) => msg.username === currentUser
  const isSystem = (msg: ChatMessage) => ['JOIN', 'LEAVE', 'SYSTEM'].includes(msg.type)
  const initials = (name: string) => name.substring(0, 2).toUpperCase()

  return (
    <div className="flex-grow-1 overflow-auto p-2 p-md-3" style={{ minHeight: 0 }}>
      {loading && (
        <div className="text-center py-3">
          <div className="spinner-border spinner-border-sm text-muted" />
          <small className="text-muted ms-2">이전 메시지 불러오는 중...</small>
        </div>
      )}

      {messages.map((msg, i) => {
        const key = msg.messageId || msg.id || `${msg.timestamp}-${i}`

        if (msg.type === 'AI_SUMMARY') {
          return (
            <div key={key} className="my-2">
              <div className="card border-info">
                <div className="card-header bg-info text-white d-flex align-items-center py-2" style={{ fontSize: '0.85rem' }}>
                  <i className="bi bi-robot me-2" /><strong>AI 요약</strong>
                  <span className="ms-auto" style={{ fontSize: '0.65rem', whiteSpace: 'nowrap' }}>{formatTime(msg.timestamp)}</span>
                </div>
                <div className="card-body py-2"><p className="card-text mb-0">{msg.content}</p></div>
              </div>
            </div>
          )
        }

        if (isSystem(msg)) {
          return (
            <div key={key} className="text-center my-1">
              <small className="text-muted fst-italic">
                {msg.content} <span style={{ fontSize: '0.65rem', whiteSpace: 'nowrap' }}>{formatTime(msg.timestamp)}</span>
              </small>
            </div>
          )
        }

        const mine = isMine(msg)
        return (
          <div key={key} className={`d-flex mb-2 ${mine ? 'justify-content-end' : 'align-items-start'}`}>
            {!mine && (
              <div className="rounded-circle bg-secondary d-flex align-items-center justify-content-center text-white fw-bold flex-shrink-0 me-2"
                style={{ width: 32, height: 32, fontSize: '0.65rem' }}>{initials(msg.username)}</div>
            )}
            <div style={{ maxWidth: 'min(75%, 400px)' }}>
              {!mine && <div className="text-muted fw-semibold mb-1" style={{ fontSize: '0.75rem' }}>{msg.username}</div>}
              <div className={`px-3 py-2 rounded-3 ${mine ? 'bg-primary text-white' : 'bg-light'}`}
                style={{ width: 'fit-content', wordBreak: 'break-word', whiteSpace: 'pre-wrap', marginLeft: mine ? 'auto' : undefined }}>
                <div>{msg.content}</div>
                <div className="mt-1" style={{ fontSize: '0.65rem', whiteSpace: 'nowrap', opacity: 0.7 }}>{formatTime(msg.timestamp)}</div>
              </div>
            </div>
          </div>
        )
      })}
      <div ref={bottomRef} />
    </div>
  )
}
