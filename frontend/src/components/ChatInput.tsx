import { useState, useRef, useCallback } from 'react'

const EMOJIS = ['😀','😃','😄','😁','😆','😅','🤣','😂','🙂','😉','😊','😇','🥰','😍','🤩','😘','😎','🤓','🤔','😐','👍','👎','👌','✌️','❤️','🧡','💛','💚','💙','💜','🔥','⭐','🎉','💯','✅','❌','🙏','💪']

interface Props {
  onSend: (content: string) => void
  disabled?: boolean
}

export default function ChatInput({ onSend, disabled }: Props) {
  const [message, setMessage] = useState('')
  const [showEmoji, setShowEmoji] = useState(false)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  const handleSubmit = useCallback(() => {
    const content = message.trim()
    if (content && !disabled) {
      onSend(content)
      setMessage('')
      setShowEmoji(false)
      if (inputRef.current) inputRef.current.style.height = 'auto'
      inputRef.current?.focus()
    }
  }, [message, disabled, onSend])

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }

  const handleInput = () => {
    const el = inputRef.current
    if (el) {
      el.style.height = 'auto'
      el.style.height = Math.min(el.scrollHeight, 120) + 'px'
    }
  }

  const insertEmoji = (emoji: string) => {
    const el = inputRef.current
    if (el) {
      const start = el.selectionStart || 0
      const end = el.selectionEnd || 0
      const next = message.substring(0, start) + emoji + message.substring(end)
      setMessage(next)
      setTimeout(() => {
        const pos = start + emoji.length
        el.setSelectionRange(pos, pos)
        el.focus()
      }, 0)
    } else {
      setMessage(prev => prev + emoji)
    }
  }

  return (
    <div className="border-top p-2" style={{ flexShrink: 0, background: 'var(--bs-body-bg)' }}>
      {showEmoji && (
        <div className="card mb-2">
          <div className="card-body p-2">
            <div className="d-flex justify-content-between align-items-center mb-1">
              <small className="text-muted fw-semibold">이모지</small>
              <button className="btn-close btn-close-sm" onClick={() => setShowEmoji(false)} />
            </div>
            <div className="d-flex flex-wrap gap-1">
              {EMOJIS.map(e => (
                <button key={e} className="btn btn-sm btn-outline-secondary border-0" style={{ fontSize: '1.2em', padding: '2px 6px' }}
                  onClick={() => insertEmoji(e)}>{e}</button>
              ))}
            </div>
          </div>
        </div>
      )}

      <form onSubmit={e => { e.preventDefault(); handleSubmit() }} className="d-flex gap-2 align-items-end">
        <div className="flex-grow-1">
          <div className="input-group">
            <textarea ref={inputRef} value={message} onChange={e => setMessage(e.target.value)} onKeyDown={handleKeyDown} onInput={handleInput}
              className="form-control" placeholder="메시지를 입력하세요..." disabled={disabled} rows={1} maxLength={1000}
              style={{ resize: 'none', minHeight: 38, maxHeight: 120, overflow: 'auto', lineHeight: 1.4, fontSize: 16 }} />
            <button type="button" className="btn btn-outline-secondary" disabled={disabled} onClick={() => setShowEmoji(v => !v)}>
              <i className="bi bi-emoji-smile" />
            </button>
          </div>
          {message.length > 800 && <div className="text-end mt-1"><small className="text-muted">{message.length}/1000</small></div>}
        </div>
        <button type="submit" className="btn btn-primary flex-shrink-0" disabled={disabled || !message.trim()} title="전송 (Enter)">
          <i className="bi bi-send" />
        </button>
      </form>
    </div>
  )
}
