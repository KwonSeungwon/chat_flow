import { useEffect } from 'react'
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/stores/authStore'
import { ErrorBoundary } from '@/components/ErrorBoundary'
import LoginPage from '@/pages/LoginPage'
import ChatPage from '@/pages/ChatPage'
import SearchPage from '@/pages/SearchPage'

import 'bootstrap/dist/css/bootstrap.min.css'
import 'bootstrap-icons/font/bootstrap-icons.css'
import 'bootstrap/dist/js/bootstrap.bundle.min.js'

function RequireAuth({ children }: { children: React.ReactNode }) {
  const location = useLocation()
  const token = localStorage.getItem('chatflow-token')
  if (!token) return <Navigate to="/login" state={{ from: location }} replace />
  return <>{children}</>
}

function NavBar() {
  const location = useLocation()
  if (location.pathname === '/login') return null

  return (
    <nav className="navbar navbar-dark bg-primary px-2 py-1" style={{ minHeight: 40 }}>
      <div className="container-fluid">
        <a className="navbar-brand fw-bold" href="/" style={{ fontSize: '1.1rem' }}>
          <i className="bi bi-chat-dots me-1" />
          <span className="d-none d-sm-inline">ChatFlow</span>
        </a>
        <div className="d-flex gap-2">
          <a className="btn btn-outline-light btn-sm" href="/search" title="검색"><i className="bi bi-search" /></a>
          <a className="btn btn-outline-light btn-sm" href="/chat" title="채팅"><i className="bi bi-chat" /></a>
        </div>
      </div>
    </nav>
  )
}

export default function App() {
  const hydrate = useAuthStore(s => s.hydrate)

  useEffect(() => { hydrate() }, [hydrate])

  return (
    <BrowserRouter>
      <ErrorBoundary>
        <div id="app-root" className="d-flex flex-column vh-100">
          <NavBar />
          <main className="flex-grow-1" style={{ overflow: 'hidden', minHeight: 0 }}>
            <Routes>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/chat/:roomId?" element={<RequireAuth><ChatPage /></RequireAuth>} />
              <Route path="/search" element={<RequireAuth><SearchPage /></RequireAuth>} />
              <Route path="*" element={<Navigate to="/chat" replace />} />
            </Routes>
          </main>
        </div>
      </ErrorBoundary>
    </BrowserRouter>
  )
}
