import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import DOMPurify from 'dompurify'
import api from '@/utils/api'

dayjs.extend(utc)

interface SearchResult {
  messageId: string
  chatRoomId: string
  username: string
  content: string
  timestamp: string
  messageType: string
}

export default function SearchPage() {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<SearchResult[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [searched, setSearched] = useState(false)

  const search = async () => {
    if (!query.trim()) return
    setLoading(true)
    setSearched(true)
    try {
      const res = await api.get(`/api/search/korean?query=${encodeURIComponent(query)}&size=20`)
      setResults(res.data.content || [])
      setTotal(res.data.totalElements || 0)
    } catch {
      setResults([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }

  const highlight = (text: string) => {
    if (!query.trim()) return text
    const escaped = query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const html = text.replace(new RegExp(`(${escaped})`, 'gi'), '<mark>$1</mark>')
    return DOMPurify.sanitize(html, { ALLOWED_TAGS: ['mark'] })
  }

  const formatTime = (ts: string) => dayjs.utc(ts).utcOffset(9).format('YYYY/MM/DD HH:mm')

  return (
    <div className="container py-4" style={{ maxWidth: 800 }}>
      <h4 className="mb-4"><i className="bi bi-search me-2" />메시지 검색</h4>

      <div className="input-group mb-4">
        <input className="form-control form-control-lg" value={query} onChange={e => setQuery(e.target.value)}
          placeholder="검색어를 입력하세요..." onKeyDown={e => e.key === 'Enter' && search()} />
        <button className="btn btn-primary" onClick={search} disabled={loading || !query.trim()}>
          {loading ? <span className="spinner-border spinner-border-sm" /> : <i className="bi bi-search" />}
          <span className="ms-1">검색</span>
        </button>
      </div>

      {searched && (
        <p className="text-muted small mb-3">총 {total}개의 결과</p>
      )}

      {results.map(r => (
        <div key={r.messageId} className="card mb-2" style={{ cursor: 'pointer' }} onClick={() => navigate(`/chat/${r.chatRoomId}`)}>
          <div className="card-body py-2 px-3">
            <div className="d-flex justify-content-between align-items-center mb-1">
              <div>
                <span className="badge bg-secondary me-2">{r.chatRoomId}</span>
                <strong className="small">{r.username}</strong>
              </div>
              <small className="text-muted" style={{ whiteSpace: 'nowrap' }}>{formatTime(r.timestamp)}</small>
            </div>
            <div className="small" dangerouslySetInnerHTML={{ __html: highlight(r.content) }} />
          </div>
        </div>
      ))}

      {searched && results.length === 0 && !loading && (
        <div className="text-center py-5 text-muted">
          <i className="bi bi-inbox" style={{ fontSize: '2rem' }} />
          <p className="mt-2">검색 결과가 없습니다.</p>
        </div>
      )}
    </div>
  )
}
