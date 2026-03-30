import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuthStore } from '@/stores/authStore'

export default function LoginPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const { login, register, guestLogin } = useAuthStore()

  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const isRegister = mode === 'register'
  const isValid = username.length >= 2 && password.length >= 4 && (!isRegister || password === confirm)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!isValid) return
    setError('')
    setLoading(true)
    try {
      if (isRegister) await register(username, password)
      else await login(username, password)
      navigate(params.get('redirect') || '/chat')
    } catch (err: any) {
      const msg = err.response?.data?.message || err.response?.data?.error
      setError(msg || (isRegister ? '이미 사용 중인 아이디입니다.' : '아이디 또는 비밀번호가 올바르지 않습니다.'))
    } finally {
      setLoading(false)
    }
  }

  const handleGuest = async () => {
    setLoading(true)
    setError('')
    try {
      await guestLogin()
      navigate('/chat')
    } catch {
      setError('게스트 로그인에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="d-flex align-items-center justify-content-center min-vh-100" style={{ background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)' }}>
      <div className="card shadow-lg border-0" style={{ maxWidth: 420, width: '100%', borderRadius: '1rem' }}>
        <div className="card-body p-4 p-md-5">
          <div className="text-center mb-4">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-primary">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
            </svg>
            <h3 className="fw-bold mt-2 mb-1">ChatFlow</h3>
            <p className="text-muted small">{isRegister ? '새 계정 만들기' : '로그인하여 채팅 시작'}</p>
          </div>

          {error && <div className="alert alert-danger py-2 small">{error}</div>}

          <form onSubmit={handleSubmit}>
            <div className="mb-3">
              <label className="form-label small fw-semibold">아이디</label>
              <input type="text" className="form-control" value={username} onChange={e => setUsername(e.target.value)} placeholder="아이디를 입력하세요" minLength={2} maxLength={50} required autoFocus />
            </div>
            <div className="mb-3">
              <label className="form-label small fw-semibold">비밀번호</label>
              <input type="password" className="form-control" value={password} onChange={e => setPassword(e.target.value)} placeholder="비밀번호를 입력하세요" minLength={4} required />
            </div>
            {isRegister && (
              <div className="mb-3">
                <label className="form-label small fw-semibold">비밀번호 확인</label>
                <input type="password" className="form-control" value={confirm} onChange={e => setConfirm(e.target.value)} placeholder="비밀번호를 다시 입력하세요" required />
                {confirm && password !== confirm && <div className="form-text text-danger">비밀번호가 일치하지 않습니다.</div>}
              </div>
            )}
            <button type="submit" className="btn btn-primary w-100 mb-3" disabled={!isValid || loading}>
              {loading && <span className="spinner-border spinner-border-sm me-1" />}
              {isRegister ? '회원가입' : '로그인'}
            </button>
          </form>

          <div className="d-flex align-items-center my-3">
            <hr className="flex-grow-1" /><span className="px-2 text-muted small">또는</span><hr className="flex-grow-1" />
          </div>

          <button className="btn btn-outline-secondary w-100 mb-3" disabled={loading} onClick={handleGuest}>게스트로 시작하기</button>

          <p className="text-center mb-0 small">
            {isRegister ? (
              <>이미 계정이 있으신가요? <a href="#" onClick={e => { e.preventDefault(); setMode('login'); setError('') }}>로그인</a></>
            ) : (
              <>계정이 없으신가요? <a href="#" onClick={e => { e.preventDefault(); setMode('register'); setError('') }}>회원가입</a></>
            )}
          </p>
        </div>
      </div>
    </div>
  )
}
