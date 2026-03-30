import React, { Component, type ReactNode } from 'react'

interface Props { children: ReactNode; fallback?: ReactNode }
interface State { hasError: boolean }

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State { return { hasError: true } }

  componentDidCatch(error: Error) { console.error('ErrorBoundary:', error) }

  render() {
    if (this.state.hasError) {
      return this.props.fallback || (
        <div className="text-center p-5">
          <h4>문제가 발생했습니다</h4>
          <button className="btn btn-primary mt-3" onClick={() => { this.setState({ hasError: false }); window.location.href = '/chat' }}>
            채팅으로 돌아가기
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
